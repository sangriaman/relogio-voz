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
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Serviço sempre ligado: ouve a palavra de ativação ("Hey Tricky"), depois ouve o pedido,
 * responde em voz alta e volta a ficar à escuta.
 */
class AssistantService : Service() {

    companion object {
        private const val TAG = "AssistantService"
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
    private val recLock = Any()
    @Volatile private var mode = Mode.LOADING
    @Volatile private var destroyed = false
    @Volatile private var recognitionGen = 0
    private var recognitionJob: Job? = null
    private var recoveryRunnable: Runnable? = null
    private var consecutiveRecErrors = 0

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
        // verificar atualizações 1 minuto depois de arrancar e depois de 6 em 6 horas
        scope.launch {
            delay(60_000L)
            while (true) {
                if (mode == Mode.WAKE || mode == Mode.LOADING) {
                    withContext(Dispatchers.IO) { try { Updater.checkAndInstall(this@AssistantService) } catch (_: Exception) { } }
                }
                delay(6 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        destroyed = true
        instance = null
        cancelRecovery()
        recognitionJob?.cancel()
        stopRecognitionSync()
        model?.close()
        tts?.shutdown()
        try { player?.release() } catch (_: Exception) { }
        player = null
        Radio.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notificação

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tricky", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("Tricky")
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

    private val ESPEAK = "com.reecedunn.espeak"
    private var ttsProblem: String? = null

    private fun setupTts() {
        // usar o eSpeak NG diretamente, se existir (não depende da voz predefinida do sistema)
        val hasEspeak = try { packageManager.getPackageInfo(ESPEAK, 0); true } catch (_: Exception) { false }
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                var r = tts?.setLanguage(Locale("pt", "PT")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (r < TextToSpeech.LANG_AVAILABLE) r = tts?.setLanguage(Locale("pt")) ?: r
                if (r < TextToSpeech.LANG_AVAILABLE) r = tts?.setLanguage(Locale("pt", "BR")) ?: r
                ttsProblem = if (r < TextToSpeech.LANG_AVAILABLE) "a voz não tem português" else null
                ttsReady = true
            } else {
                ttsProblem = if (hasEspeak) "o eSpeak não arrancou" else "não há nenhuma voz instalada (instala o eSpeak NG)"
            }
        }
        // usar sempre o motor escolhido em Definições → Saída de texto para voz
        tts = TextToSpeech(this, listener)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                main.post {
                    if (!afterSpeakPending) return@post
                    if (utteranceId?.startsWith("file-") == true) {
                        val token = utteranceId.removePrefix("file-").toIntOrNull() ?: speakToken
                        if (token == speakToken) playBunny(token)
                    } else {
                        speechFinished()
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post {
                    if (!afterSpeakPending) return@post
                    // se gravar para ficheiro falhar, falar diretamente com a voz normal
                    if (utteranceId?.startsWith("file-") == true) speakPlain(pendingText) else speechFinished()
                }
            }
        })
    }

    private var speakToken = 0

    private fun say(text: String, then: (() -> Unit)?) {
        afterSpeak = then
        if (text.isBlank()) {
            speechFinished()
            return
        }
        val token = ++speakToken
        afterSpeakPending = true
        pendingText = text
        Bus.mode.value = "speaking"
        // rede de segurança: se a voz nunca avisar que acabou, não ficar preso a "falar"
        main.postDelayed({ if (token == speakToken && afterSpeakPending) speechFinished() }, 8000L + text.length * 120L)

        scope.launch {
            // 1.º: voz gravada (tugão), construída fora da thread principal
            val clip = withContext(Dispatchers.IO) {
                try { ClipVoice.build(this@AssistantService, text) } catch (_: Exception) { null }
            }
            if (token != speakToken || !afterSpeakPending) return@launch
            if (clip != null) {
                playFile(clip, token)
                return@launch
            }
            if (!ttsReady) {
                if (ttsProblem != null) Bus.status.value = "Sem voz: $ttsProblem"
                speechFinished()
                return@launch
            }
            if (Prefs.bunnyVoice(this@AssistantService)) {
                // gerar a fala num ficheiro e tocá-la mais aguda: voz de coelhinho
                val f = File(cacheDir, "fala.wav")
                val r = tts?.synthesizeToFile(text, null, f, "file-$token")
                if (r == TextToSpeech.SUCCESS) return@launch
            }
            speakPlain(text)
        }
    }

    private var pendingText = ""
    private var player: android.media.MediaPlayer? = null

    private fun speakPlain(text: String) {
        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tricky-$speakToken")
        if (r != TextToSpeech.SUCCESS) {
            Bus.status.value = "Sem voz: o motor de voz recusou falar"
            speechFinished()
        }
    }

    private fun playBunny(token: Int) = playFile(File(cacheDir, "fala.wav"), token)

    private fun playFile(f: File, token: Int) {
        try {
            player?.release()
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(f.absolutePath)
            mp.setOnPreparedListener { ready ->
                if (destroyed || token != speakToken || !afterSpeakPending || player !== ready) {
                    try { ready.release() } catch (_: Exception) { }
                    return@setOnPreparedListener
                }
                try {
                    val pitch = if (Prefs.bunnyVoice(this)) Prefs.bunnyPitch(this) else 1f
                    ready.playbackParams = ready.playbackParams
                        .setPitch(pitch)
                        .setSpeed(if (pitch > 1.05f) 1.08f else 1f)
                    ready.start()
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao reproduzir áudio da fala", e)
                    try { ready.release() } catch (_: Exception) { }
                    if (player === ready) player = null
                    speakPlain(pendingText)
                }
            }
            mp.setOnCompletionListener { done ->
                try { done.release() } catch (_: Exception) { }
                if (player === done) player = null
                if (token == speakToken && afterSpeakPending) speechFinished()
            }
            mp.setOnErrorListener { failed, _, _ ->
                try { failed.release() } catch (_: Exception) { }
                if (player === failed) player = null
                if (token == speakToken && afterSpeakPending) speakPlain(pendingText)
                true
            }
            player = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao preparar MediaPlayer", e)
            speakPlain(pendingText)
        }
    }

    private var afterSpeakPending = false

    private fun speechFinished() {
        afterSpeakPending = false
        speakToken++
        try { player?.release() } catch (_: Exception) { }
        player = null
        val a = afterSpeak
        afterSpeak = null
        releaseFocus()
        Bus.mode.value = "idle"
        try { a?.invoke() } catch (e: Exception) { Log.w(TAG, "Erro em afterSpeech", e) }
        // dar um momento para o som acabar antes de voltar a ouvir
        main.postDelayed({ if (!destroyed) listenForWakeWord() }, 600)
        // limpar o balão passado algum tempo, para voltar a mostrar o estado
        val shown = Bus.answer.value
        main.postDelayed({
            if (Bus.answer.value == shown && mode == Mode.WAKE) {
                Bus.answer.value = ""
                Bus.heard.value = ""
            }
        }, 20_000L)
    }

    // ---------------------------------------------------------------- modelo de voz

    private suspend fun loadModel() {
        val dir = File(filesDir, "model")
        try {
            if (!File(dir, "final.mdl").exists() && !File(dir, "am/final.mdl").exists()) {
                Bus.status.value = "A descarregar o modelo de voz (≈ 30 MB)…"
                withContext(Dispatchers.IO) { downloadModel(dir) }
            }
            Bus.status.value = "A carregar o modelo de voz…"
            model = withContext(Dispatchers.IO) { Model(dir.absolutePath) }
            listenForWakeWord()
        } catch (e: Exception) {
            Log.e(TAG, "Erro no modelo de voz", e)
            Bus.status.value = "Erro no modelo de voz. Reinicia a app para tentar outra vez."
            dir.deleteRecursively()
        }
    }

    private fun downloadModel(target: File) {
        val tmp = File(filesDir, "model.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        val downloadClient = Http.client.newBuilder()
            .callTimeout(5, TimeUnit.MINUTES)
            .build()
        val req = Request.Builder().url(MODEL_URL).header("User-Agent", Http.UA).build()
        downloadClient.newCall(req).execute().use { r ->
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

    /**
     * O modelo português não conhece "tricky", por isso usamos palavras portuguesas
     * que soam parecido ("trico", "trica"…) evitando palavras demasiado comuns.
     */
    private fun wakeVariants(): List<String> {
        val w = Txt.norm(Prefs.wakeWord(this)).removePrefix("hey ").removePrefix("ei ").trim()
        return if (w.contains("trick") || w.contains("triki") || w.contains("trique")) {
            listOf("trico", "trica", "trique", "truque", "trinca", "tique")
        } else {
            w.split(" ").filter { it.isNotBlank() }.ifEmpty { listOf("tricky") }
        }
    }

    private fun wakeGrammar(variants: List<String>): String {
        val phrases = variants + variants.map { "ei $it" } + variants.map { "ai $it" } + "[unk]"
        return JSONArray(phrases).toString()
    }

    private fun cancelRecovery() {
        recoveryRunnable?.let { main.removeCallbacks(it) }
        recoveryRunnable = null
    }

    private fun scheduleRecovery(gen: Int, exception: Exception?) {
        Log.w(TAG, "Erro no reconhecimento de voz", exception)
        main.post {
            if (destroyed || gen != recognitionGen) return@post
            if (mode == Mode.COMMAND) releaseFocus()
            if (mode == Mode.BUSY) return@post
            consecutiveRecErrors++
            val backoff = minOf(5000L, 1200L * consecutiveRecErrors)
            Bus.status.value = "A reiniciar microfone…"
            cancelRecovery()
            val task = Runnable {
                if (!destroyed && mode != Mode.BUSY) listenForWakeWord()
            }
            recoveryRunnable = task
            scope.launch(Dispatchers.IO) { stopRecognitionSync() }
            main.postDelayed(task, backoff)
        }
    }

    private fun stopRecognitionSync() {
        synchronized(recLock) {
            speech?.let {
                try { it.stop() } catch (_: Exception) { }
                try { it.shutdown() } catch (_: Exception) { }
            }
            speech = null
            try { recognizer?.close() } catch (_: Exception) { }
            recognizer = null
        }
    }

    private fun startRecognitionSync(rec: Recognizer, listener: RecognitionListener, timeoutMs: Int?): Boolean {
        synchronized(recLock) {
            stopRecognitionSync()
            recognizer = rec
            return try {
                val s = SpeechService(rec, SAMPLE_RATE)
                speech = s
                if (timeoutMs != null) s.startListening(listener, timeoutMs) else s.startListening(listener)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Não consigo usar o microfone", e)
                try { rec.close() } catch (_: Exception) { }
                recognizer = null
                false
            }
        }
    }

    /** Fica à espera da palavra de ativação (gramática pequena = leve para o relógio). */
    fun listenForWakeWord() {
        val m = model ?: return
        if (destroyed) return
        cancelRecovery()
        recognitionJob?.cancel()
        val gen = ++recognitionGen
        mode = Mode.WAKE
        Bus.status.value = "À escuta — diz \"${Prefs.wakeWord(this)}\""
        Bus.mode.value = "idle"

        val variants = wakeVariants()
        val grammar = wakeGrammar(variants)

        recognitionJob = scope.launch {
            val started = withContext(Dispatchers.IO) {
                val rec = try {
                    Recognizer(m, SAMPLE_RATE, grammar).apply { setWords(true) }
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao criar Recognizer de wake word", e)
                    return@withContext false
                }
                if (destroyed || gen != recognitionGen || mode != Mode.WAKE) {
                    try { rec.close() } catch (_: Exception) { }
                    return@withContext true
                }
                var lastPartialCandidate: String? = null
                val listener = object : SimpleListener() {
                    override fun onPartialResult(hypothesis: String?) {
                        if (gen != recognitionGen || mode != Mode.WAKE) return
                        val p = hypothesis?.let { JSONObject(it).optString("partial") }?.trim() ?: ""
                        if (p.isBlank()) {
                            lastPartialCandidate = null
                            return
                        }
                        val words = p.split(' ').filter { it.isNotBlank() }
                        for (i in words.indices) {
                            val w = words[i]
                            if (w !in variants) continue
                            val hasPrefix = i > 0 && (words[i - 1] == "ei" || words[i - 1] == "ai")
                            if (hasPrefix) {
                                main.post { if (gen == recognitionGen && mode == Mode.WAKE) listenForCommand() }
                                return
                            }
                            // Sem prefixo ("ei"/"ai"): não disparar imediatamente em palavras curtas;
                            // exigir >= 6 letras confirmadas em dois parciais consecutivos.
                            if (w.length >= 6) {
                                if (lastPartialCandidate == w) {
                                    main.post { if (gen == recognitionGen && mode == Mode.WAKE) listenForCommand() }
                                } else {
                                    lastPartialCandidate = w
                                }
                                return
                            }
                        }
                        lastPartialCandidate = null
                    }

                    override fun onResult(hypothesis: String?) {
                        if (gen != recognitionGen || mode != Mode.WAKE) return
                        lastPartialCandidate = null
                        val text = hypothesis?.let { JSONObject(it).optString("text") } ?: ""
                        // mostrar o que percebeu ajuda a afinar a palavra de ativação
                        if (text.isNotBlank() && text != "[unk]") {
                            main.post {
                                if (mode == Mode.WAKE) {
                                    Bus.status.value = "Ouvi \"$text\" — diz \"${Prefs.wakeWord(this@AssistantService)}\""
                                }
                            }
                        }
                        if (isWake(hypothesis, variants)) {
                            main.post { if (gen == recognitionGen && mode == Mode.WAKE) listenForCommand() }
                        }
                    }

                    override fun onError(exception: Exception?) {
                        scheduleRecovery(gen, exception)
                    }
                }
                startRecognitionSync(rec, listener, null)
            }
            if (started) {
                consecutiveRecErrors = 0
            } else if (gen == recognitionGen && !destroyed) {
                scheduleRecovery(gen, IllegalStateException("Falha ao iniciar microfone"))
            }
        }
    }

    private fun isWake(json: String?, variants: List<String>): Boolean {
        if (json.isNullOrBlank()) return false
        val o = JSONObject(json)
        val words = o.optJSONArray("result") ?: return false
        var prevWord = ""
        for (i in 0 until words.length()) {
            val w = words.getJSONObject(i)
            val word = w.optString("word")
            val conf = w.optDouble("conf", 1.0)
            if (word in variants) {
                val hasPrefix = prevWord == "ei" || prevWord == "ai"
                val minConf = when {
                    hasPrefix -> 0.45
                    word.length < 6 -> 0.60
                    else -> 0.55
                }
                if (conf >= minConf) return true
            }
            prevWord = word
        }
        return false
    }

    /** Chamado pela palavra de ativação ou pelo botão do ecrã. */
    fun listenForCommand() {
        val m = model ?: return
        if (destroyed || mode == Mode.COMMAND || mode == Mode.BUSY) return
        cancelRecovery()
        recognitionJob?.cancel()
        val gen = ++recognitionGen
        mode = Mode.COMMAND
        requestFocus()
        Bus.heard.value = ""
        Bus.status.value = "Diz…"
        Bus.mode.value = "listening"
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ tone.release() }, 400)
        } catch (_: Exception) { }

        recognitionJob = scope.launch {
            withContext(Dispatchers.IO) { stopRecognitionSync() }
            delay(250)
            if (destroyed || gen != recognitionGen || mode != Mode.COMMAND) return@launch

            val started = withContext(Dispatchers.IO) {
                val rec = try {
                    Recognizer(m, SAMPLE_RATE)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao criar Recognizer de comando", e)
                    return@withContext false
                }
                if (destroyed || gen != recognitionGen || mode != Mode.COMMAND) {
                    try { rec.close() } catch (_: Exception) { }
                    return@withContext true
                }
                val lastPartial = java.util.concurrent.atomic.AtomicReference("")
                val listener = object : SimpleListener() {
                    override fun onPartialResult(hypothesis: String?) {
                        if (gen != recognitionGen || mode != Mode.COMMAND) return
                        val p = hypothesis?.let { JSONObject(it).optString("partial") }?.trim() ?: ""
                        if (p.isNotBlank()) {
                            lastPartial.set(p)
                            Bus.heard.value = p
                        }
                    }

                    override fun onResult(hypothesis: String?) {
                        if (gen != recognitionGen || mode != Mode.COMMAND) return
                        val text = hypothesis?.let { JSONObject(it).optString("text") }?.trim() ?: ""
                        if (text.isNotBlank()) {
                            main.post { if (gen == recognitionGen && mode == Mode.COMMAND) handleCommand(text) }
                        }
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        if (gen != recognitionGen || mode != Mode.COMMAND) return
                        val text = hypothesis?.let { JSONObject(it).optString("text") }?.trim() ?: ""
                        val chosen = text.ifBlank { lastPartial.get().trim() }
                        if (chosen.isNotBlank()) {
                            main.post { if (gen == recognitionGen && mode == Mode.COMMAND) handleCommand(chosen) }
                        }
                    }

                    override fun onTimeout() {
                        main.post {
                            if (gen != recognitionGen || mode != Mode.COMMAND) return@post
                            val fallback = lastPartial.get().trim()
                            if (fallback.isNotBlank()) {
                                handleCommand(fallback)
                            } else {
                                releaseFocus()
                                Bus.status.value = "Não ouvi nada."
                                listenForWakeWord()
                            }
                        }
                    }

                    override fun onError(exception: Exception?) {
                        scheduleRecovery(gen, exception)
                    }
                }
                startRecognitionSync(rec, listener, 8000)
            }
            if (started) {
                consecutiveRecErrors = 0
            } else if (gen == recognitionGen && !destroyed) {
                scheduleRecovery(gen, IllegalStateException("Falha ao iniciar escuta de comando"))
            }
        }
    }

    /** Pedido vindo de um botão do ecrã (sem voz). */
    fun runText(text: String) {
        if (mode == Mode.BUSY || (model == null && text.isBlank())) return
        handleCommand(text)
    }

    private fun handleCommand(text: String) {
        cancelRecovery()
        recognitionJob?.cancel()
        recognitionGen++
        mode = Mode.BUSY
        Bus.heard.value = text
        Bus.status.value = "A pensar…"
        Bus.mode.value = "thinking"
        scope.launch {
            val answer = withContext(Dispatchers.IO) {
                stopRecognitionSync()
                brain.answer(this@AssistantService, text)
            }
            val shown = (answer.display ?: answer.speech).trim()
            if (shown.isNotBlank()) Bus.answer.value = shown
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
