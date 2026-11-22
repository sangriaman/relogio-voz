package pt.sangriaman.relogiovoz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import pt.sangriaman.relogiovoz.skills.Radio
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Serviço sempre ligado: ouve a palavra de ativação ("Manel"), depois ouve o pedido,
 * responde em voz alta e volta a ficar à escuta.
 */
class AssistantService : Service() {

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
        private const val CHANNEL = "manel"
        private const val SAMPLE_RATE = 16000f

        @Volatile
        var instance: AssistantService? = null
            private set

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AssistantService::class.java))
        }
    }

    private enum class Mode { LOADING, WAKE, COMMAND, BUSY }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val brain = Brain()

    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var mode = Mode.LOADING

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var afterSpeak: (() -> Unit)? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "manel:escuta").apply { acquire() }
        setupTts()
        scope.launch { loadModel() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        stopRecognition()
        model?.close()
        tts?.shutdown()
        Radio.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notificação

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Manel", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Manel")
            .setContentText("Diz \"${Prefs.wakeWord(this)}\" para falar comigo")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(
            1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    // ---------------------------------------------------------------- voz (saída)

    private fun setupTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("pt", "PT"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale("pt"))
                }
                ttsReady = true
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { speechFinished() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { speechFinished() } }
        })
    }

    private fun say(text: String, then: (() -> Unit)?) {
        afterSpeak = then
        if (text.isBlank() || !ttsReady) {
            speechFinished()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "manel-${System.currentTimeMillis()}")
    }

    private fun speechFinished() {
        val a = afterSpeak
        afterSpeak = null
        releaseFocus()
        try { a?.invoke() } catch (_: Exception) { }
        // dar um momento para o som acabar antes de voltar a ouvir
        main.postDelayed({ listenForWakeWord() }, 600)
    }

    // ---------------------------------------------------------------- modelo de voz

    private suspend fun loadModel() {
        val dir = File(filesDir, "model")
        try {
            if (!File(dir, "conf").exists() && !File(dir, "am").exists()) {
                Bus.status.value = "A descarregar o modelo de voz (≈ 30 MB)…"
                withContext(Dispatchers.IO) { downloadModel(dir) }
            }
            Bus.status.value = "A carregar o modelo de voz…"
            model = withContext(Dispatchers.IO) { Model(dir.absolutePath) }
            listenForWakeWord()
        } catch (e: Exception) {
            Bus.status.value = "Erro no modelo de voz: ${e.message}. Reinicia a app para tentar outra vez."
            dir.deleteRecursively()
        }
    }

    private fun downloadModel(target: File) {
        val tmp = File(filesDir, "model.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val req = Request.Builder().url(MODEL_URL).header("User-Agent", Http.UA).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            val total = r.body!!.contentLength()
            var read = 0L
            val counting = object : java.io.FilterInputStream(r.body!!.byteStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        if (total > 0) Bus.status.value = "A descarregar o modelo de voz: ${read * 100 / total}%"
                    }
                    return n
                }
            }
            ZipInputStream(counting).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    // retirar a pasta de topo "vosk-model-small-pt-0.3/"
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isNotEmpty() && !rel.contains("..")) {
                        val out = File(tmp, rel)
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                    }
                    e = zip.nextEntry
                }
            }
        }
        target.deleteRecursively()
        if (!tmp.renameTo(target)) throw java.io.IOException("não consegui guardar o modelo")
    }

    // ---------------------------------------------------------------- reconhecimento

    private fun wakeVariants(): List<String> {
        val w = Txt.norm(Prefs.wakeWord(this))
        val list = mutableListOf(w)
        if (w == "manel") list += listOf("manuel", "manoel")
        return list.distinct()
    }

    private fun stopRecognition() {
        speech?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.shutdown() } catch (_: Exception) { }
        }
        speech = null
        recognizer?.close()
        recognizer = null
    }

    private fun startRecognition(rec: Recognizer, listener: RecognitionListener, timeoutMs: Int?) {
        stopRecognition()
        recognizer = rec
        try {
            val s = SpeechService(rec, SAMPLE_RATE)
            speech = s
            if (timeoutMs != null) s.startListening(listener, timeoutMs) else s.startListening(listener)
        } catch (e: Exception) {
            Bus.status.value = "Não consigo usar o microfone: ${e.message}"
        }
    }

    /** Fica à espera da palavra de ativação (gramática pequena = leve para o relógio). */
    fun listenForWakeWord() {
        val m = model ?: return
        mode = Mode.WAKE
        Bus.status.value = "À escuta — diz \"${Prefs.wakeWord(this)}\""
        val grammar = JSONArray(wakeVariants() + "[unk]").toString()
        val rec = Recognizer(m, SAMPLE_RATE, grammar)
        rec.setWords(true)
        startRecognition(rec, object : SimpleListener() {
            override fun onResult(hypothesis: String?) {
                if (mode == Mode.WAKE && isWake(hypothesis)) main.post { listenForCommand() }
            }
        }, null)
    }

    private fun isWake(json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        val o = JSONObject(json)
        val words = o.optJSONArray("result") ?: return false
        val variants = wakeVariants()
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            if (w.optString("word") in variants && w.optDouble("conf", 1.0) >= 0.7) return true
        }
        return false
    }

    /** Chamado pela palavra de ativação ou pelo botão do ecrã. */
    fun listenForCommand() {
        val m = model ?: return
        if (mode == Mode.COMMAND || mode == Mode.BUSY) return
        mode = Mode.COMMAND
        stopRecognition()
        requestFocus()
        Bus.heard.value = ""
        Bus.status.value = "Diz…"
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tone.release() }, 400)
        } catch (_: Exception) { }
        main.postDelayed({
            val rec = Recognizer(m, SAMPLE_RATE)
            startRecognition(rec, object : SimpleListener() {
                override fun onPartialResult(hypothesis: String?) {
                    val p = hypothesis?.let { JSONObject(it).optString("partial") } ?: ""
                    if (p.isNotBlank()) Bus.heard.value = p
                }

                override fun onResult(hypothesis: String?) {
                    val text = hypothesis?.let { JSONObject(it).optString("text") } ?: ""
                    if (mode == Mode.COMMAND && text.isNotBlank()) main.post { handleCommand(text) }
                }

                override fun onTimeout() {
                    main.post {
                        if (mode == Mode.COMMAND) {
                            releaseFocus()
                            Bus.status.value = "Não ouvi nada."
                            listenForWakeWord()
                        }
                    }
                }
            }, 8000)
        }, 250)
    }

    private fun handleCommand(text: String) {
        mode = Mode.BUSY
        stopRecognition()
        Bus.heard.value = text
        Bus.status.value = "A pensar…"
        scope.launch {
            val answer = withContext(Dispatchers.IO) { brain.answer(this@AssistantService, text) }
            if (answer.speech.isNotBlank()) Bus.answer.value = answer.speech
            Bus.status.value = "A responder…"
            say(answer.speech, answer.afterSpeech)
        }
    }

    // ---------------------------------------------------------------- foco de áudio

    private fun requestFocus() {
        Radio.duck(true)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focus = req
        audio.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        Radio.duck(false)
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private open class SimpleListener : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) {}
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {}
        override fun onTimeout() {}
    }
}
