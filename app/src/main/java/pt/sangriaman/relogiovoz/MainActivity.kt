package pt.sangriaman.relogiovoz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.sangriaman.relogiovoz.skills.HA
import pt.sangriaman.relogiovoz.skills.Radio
import pt.sangriaman.relogiovoz.skills.WeatherSkill
import pt.sangriaman.relogiovoz.skills.sendMediaKey

/**
 * Ecrã inicial do relógio: o Tricky à esquerda; à direita as horas
 * e os widgets (tempo, música, botões da casa) e o acesso às apps.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var plush: PlushView
    private lateinit var bubble: TextView
    private lateinit var weather: TextView
    private lateinit var music: TextView
    private lateinit var homeRow: LinearLayout
    private lateinit var overlayButton: Button

    // fundo turquesa; cartões num turquesa mais escuro para o texto branco se ler bem
    private val turquoise = Color.rgb(48, 196, 190)
    private val accent = Color.WHITE
    private val card = Color.argb(110, 0, 70, 75)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        scope.launch { Bus.mode.collect { plush.mode = it } }
        scope.launch { Bus.status.collect { refreshBubble() } }
        scope.launch { Bus.heard.collect { refreshBubble() } }
        scope.launch { Bus.answer.collect { refreshBubble() } }
        scope.launch {
            Bus.nowPlaying.collect { music.text = if (it.isBlank()) "🎵 Diz \"toca…\" para ouvir música" else it }
        }
        scope.launch { weatherLoop() }

        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        overlayButton.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        if (hasMic()) AssistantService.start(this)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // Sendo o ecrã inicial, o botão "voltar" não deve fechar a app.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        // Botão do meio do relógio (KEY_MICMUTE no Linux = KEYCODE_MUTE no Android)
        if (keyCode == KeyEvent.KEYCODE_MUTE || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (event?.repeatCount == 0) AssistantService.instance?.listenForCommand()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions() {
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasMic()) AssistantService.start(this) else Bus.status.value = "Preciso de acesso ao microfone."
    }

    // ------------------------------------------------------------------ widgets

    private fun refreshBubble() {
        val sb = StringBuilder()
        if (Bus.heard.value.isNotBlank()) sb.append("«${Bus.heard.value}»\n")
        if (Bus.answer.value.isNotBlank() && Bus.mode.value != "listening") sb.append(Bus.answer.value)
        val text = sb.toString().trim()
        bubble.text = if (text.isBlank()) Bus.status.value else text
    }

    private suspend fun weatherLoop() {
        while (true) {
            try {
                val city = Prefs.city(this)
                val f = withContext(Dispatchers.IO) { WeatherSkill.fetch(city) }
                weather.text = if (f != null) WeatherSkill.widgetLine(f) else "Cidade \"$city\" não encontrada"
            } catch (e: Exception) {
                if (weather.text.isNullOrBlank()) weather.text = "Sem dados do tempo"
            }
            delay(30 * 60 * 1000L)
        }
    }

    private suspend fun homeLoop() {
        while (true) {
            delay(60 * 1000L)
            refreshHome()
        }
    }

    /** Botões da casa, com cor conforme o estado (ligado/desligado). */
    private suspend fun refreshHome() {
        val names = Prefs.homeButtons(this)
        if (!HA.configured(this) || names.isEmpty()) {
            homeRow.visibility = View.GONE
            return
        }
        val entities = try { withContext(Dispatchers.IO) { HA.states(this@MainActivity) } } catch (e: Exception) { null }
        homeRow.removeAllViews()
        homeRow.visibility = View.VISIBLE
        if (entities == null) {
            homeRow.addView(chip("🏠 Home Assistant indisponível", false) {})
            return
        }
        for (name in names) {
            val e = HA.best(entities, name) ?: continue
            homeRow.addView(chip((if (e.isOn) "● " else "○ ") + e.name, e.isOn) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        try { HA.call(this@MainActivity, e, if (e.isOn) "turn_off" else "turn_on") } catch (_: Exception) { }
                    }
                    delay(800)
                    refreshHome()
                }
            })
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(turquoise)
            setPadding(12, 8, 12, 8)
        }

        // --- esquerda: o Tricky e o balão de fala
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        plush = PlushView(this).apply {
            setOnClickListener { AssistantService.instance?.listenForCommand() }
        }
        bubble = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            maxLines = 4
            setPadding(14, 10, 14, 10)
            background = rounded(card, 18f)
        }
        left.addView(plush, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        left.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // --- direita: horas e widgets
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 0, 0)
        }
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = 48f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"
            format12Hour = "EEEE, d 'de' MMMM"
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        weather = widget("A carregar o tempo…")
        music = widget("")
        val musicControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        musicControls.addView(small("⏮") { sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
        musicControls.addView(small("⏯") {
            if (Radio.isPlaying()) Radio.stop().also { Bus.nowPlaying.value = "" }
            else sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        })
        musicControls.addView(small("⏭") { sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT) })
        musicControls.addView(small("⏹") {
            Radio.stop()
            sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PAUSE)
            Bus.nowPlaying.value = ""
        })

        homeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(small("▦ Apps") { startActivity(Intent(this, AppsActivity::class.java)) })
        bottom.addView(small("🎤 Falar") { AssistantService.instance?.listenForCommand() })
        bottom.addView(small("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        overlayButton = small("Permitir abrir apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        bottom.addView(overlayButton)

        right.addView(clock)
        right.addView(date)
        right.addView(weather, margins())
        right.addView(quickRow(), margins())
        right.addView(music, margins())
        right.addView(musicControls)
        right.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        right.addView(bottom)

        root.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.25f))
        return root
    }

    /** Botões de pedidos rápidos para quem não quer (ou não consegue) falar. */
    private fun quickRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun ask(text: String) {
            val s = AssistantService.instance
            if (s == null) Bus.status.value = "O Tricky ainda está a acordar…" else s.runText(text)
        }
        row.addView(chip("☀️ Tempo", false) { ask("como está o tempo") })
        row.addView(chip("🕐 Horas", false) { ask("que horas são") })
        row.addView(chip("🎵 Música", false) { musicDialog { ask("toca $it") } })
        row.addView(chip("📻 Rádio", false) { radioDialog { ask("põe a rádio $it") } })
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun musicDialog(onPick: (String) -> Unit) {
        val presets = arrayOf("Músicas infantis", "Canções de embalar", "Panda e os Caricas", "Xana Toc Toc", "Músicas da Disney", "✏️ Escrever…")
        android.app.AlertDialog.Builder(this)
            .setTitle("O que queres ouvir?")
            .setItems(presets) { _, i ->
                if (i == presets.lastIndex) typeDialog("Que música?", onPick) else onPick(presets[i])
            }
            .show()
    }

    private fun radioDialog(onPick: (String) -> Unit) {
        val presets = arrayOf("Comercial", "RFM", "Cidade FM", "Mega Hits", "Antena 1", "Renascença")
        android.app.AlertDialog.Builder(this)
            .setTitle("Que rádio?")
            .setItems(presets) { _, i -> onPick(presets[i]) }
            .show()
    }

    private fun typeDialog(title: String, onPick: (String) -> Unit) {
        val input = android.widget.EditText(this)
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Tocar") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) onPick(t)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun margins() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 8 }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun widget(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(14, 10, 14, 10)
        background = rounded(card, 14f)
        maxLines = 2
    }

    private fun small(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 17f
        minWidth = 0
        minimumWidth = 0
        setPadding(22, 0, 22, 0)
        setOnClickListener { onClick() }
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 19f
        setTextColor(if (on) Color.rgb(0, 90, 95) else Color.WHITE)
        setPadding(26, 16, 26, 16)
        background = rounded(if (on) accent else card, 30f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = 8 }
    }
}
