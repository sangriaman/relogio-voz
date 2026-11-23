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
import kotlin.math.roundToInt

/**
 * Ecrã inicial do relógio: o Tricky à esquerda; à direita as horas
 * e os widgets (tempo, música, botões da casa) e o acesso às apps.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var plush: PlushView
    private lateinit var bubble: TextView
    private lateinit var weather: TextView
    private lateinit var weatherSub: TextView
    private lateinit var music: TextView
    private lateinit var homeRow: LinearLayout
    private lateinit var overlayButton: Button
    private lateinit var night: View
    private var peekUntil = 0L

    // fundo turquesa; cartões num turquesa mais escuro para o texto branco se ler bem
    private val turquoise = Color.rgb(48, 196, 190)
    private val accent = Color.WHITE
    private val card = Color.argb(110, 0, 70, 75)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(android.widget.FrameLayout(this).apply {
            addView(buildUi())
            addView(buildNight())
        })
        scope.launch {
            while (true) {
                applyNight()
                delay(20_000L)
            }
        }

        scope.launch { Bus.mode.collect { plush.mode = it } }
        scope.launch { Bus.status.collect { refreshBubble() } }
        scope.launch { Bus.heard.collect { refreshBubble() } }
        scope.launch { Bus.answer.collect { refreshBubble() } }
        scope.launch {
            Bus.nowPlaying.collect { music.text = if (it.isBlank()) "🎵" else it }
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
                if (f != null) {
                    val cur = f.json.getJSONObject("current")
                    val d = f.json.getJSONObject("daily")
                    val code = cur.getInt("weather_code")
                    val t = cur.getDouble("temperature_2m").roundToInt()
                    val max = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
                    val min = d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
                    val rain = d.getJSONArray("precipitation_probability_max").optInt(0, 0)
                    weather.text = "${WeatherSkill.icon(code)} $t°"
                    weatherSub.text = "${f.name}\n↑$max° ↓$min° · 💧$rain%"
                } else {
                    weather.text = "?"
                    weatherSub.text = "Cidade \"$city\" não encontrada"
                }
            } catch (e: Exception) {
                if (weather.text == "…") weatherSub.text = "Sem dados do tempo"
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

    // ------------------------------------------------------------------ modo noite

    /** Ecrã escuro só com as horas; tocar mostra o ecrã normal durante 1 minuto. */
    private fun buildNight(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                peekUntil = System.currentTimeMillis() + 60_000L
                applyNight()
                scope.launch { delay(61_000L); applyNight() }
            }
        }
        box.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.rgb(0, 120, 125))
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(40, 220, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        box.addView(TextView(this).apply {
            text = "Dorme bem 🌙"
            textSize = 16f
            setTextColor(Color.rgb(0, 90, 95))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })
        night = box
        return box
    }

    private fun applyNight() {
        val on = Routine.isNight(this) && System.currentTimeMillis() > peekUntil
        night.visibility = if (on) View.VISIBLE else View.GONE
        val lp = window.attributes
        val want = if (on) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lp.screenBrightness != want) {
            lp.screenBrightness = want
            window.attributes = lp
        }
    }

    // ------------------------------------------------------------------ layout

    // Cores dos mosaicos, ao estilo do Windows Phone
    private val tClock = Color.rgb(0, 104, 108)
    private val tWeather = Color.rgb(240, 150, 9)
    private val tMusic = Color.rgb(162, 0, 255)
    private val tRadio = Color.rgb(229, 20, 0)
    private val tTalk = Color.rgb(27, 161, 226)
    private val tApps = Color.rgb(51, 153, 51)
    private val tBar = Color.argb(120, 0, 70, 75)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun ask(text: String) {
        val s = AssistantService.instance
        if (s == null) Bus.status.value = "O Tricky ainda está a acordar…" else s.runText(text)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(turquoise)
            setPadding(dp(6), dp(6), dp(6), dp(6))
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
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(card, 18f)
        }
        left.addView(plush, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        left.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // --- direita: grelha de mosaicos
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), 0, 0, 0)
        }

        // Mosaico largo das horas (tocar = o Tricky diz as horas)
        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            maxLines = 1
            setAutoSizeTextTypeUniformWithConfiguration(30, 140, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"
            format12Hour = "EEEE, d 'de' MMMM"
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
        }
        val clockTile = tileBox(tClock) { ask("que horas são") }.apply {
            addView(clock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(date)
        }

        // Tempo
        weather = TextView(this).apply {
            text = "…"
            setTextColor(Color.WHITE)
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(18, 64, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        weatherSub = TextView(this).apply {
            text = "Tempo"
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 2
        }
        val weatherTile = tileBox(tWeather) { ask("como está o tempo") }.apply {
            addView(weather, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(weatherSub)
        }

        // Música (mostra o que está a tocar)
        music = TextView(this).apply {
            text = "🎵"
            setTextColor(Color.WHITE)
            maxLines = 3
            gravity = Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(13, 44, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        val musicTile = tileBox(tMusic) { musicDialog { ask("toca $it") } }.apply {
            addView(music, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(label("Música"))
        }

        val radioTile = iconTile(tRadio, "📻", "Rádio") { radioDialog { ask("põe a rádio $it") } }
        val talkTile = iconTile(tTalk, "🎤", "Falar") { AssistantService.instance?.listenForCommand() }
        val appsTile = iconTile(tApps, "▦", "Apps") { startActivity(Intent(this, AppsActivity::class.java)) }

        // Barra fina: controlos de música e definições
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(barButton("⏮") { sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
        bar.addView(barButton("⏯") {
            if (Radio.isPlaying()) Radio.stop().also { Bus.nowPlaying.value = "" }
            else sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        })
        bar.addView(barButton("⏭") { sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_NEXT) })
        bar.addView(barButton("⏹") {
            Radio.stop()
            sendMediaKey(this, KeyEvent.KEYCODE_MEDIA_PAUSE)
            Bus.nowPlaying.value = ""
        })
        bar.addView(barButton("⚙") { startActivity(Intent(this, SettingsActivity::class.java)) })
        overlayButton = Button(this).apply {
            text = "Permitir abrir apps"
            isAllCaps = false
            textSize = 13f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        bar.addView(overlayButton)

        homeRow = LinearLayout(this).apply { visibility = View.GONE }

        grid.addView(row(1.35f, clockTile to 1f))
        grid.addView(row(1.15f, weatherTile to 1.15f, musicTile to 1f))
        grid.addView(row(0.9f, radioTile to 1f, talkTile to 1f, appsTile to 1f))
        grid.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply { topMargin = dp(4) })

        root.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        root.addView(grid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f))
        return root
    }

    /** Uma linha da grelha com mosaicos lado a lado (pares vista/peso). */
    private fun row(weight: Float, vararg tiles: Pair<View, Float>): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tiles.forEachIndexed { i, (v, w) ->
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w).apply {
                if (i > 0) leftMargin = dp(4)
            })
        }
        r.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, weight).apply { topMargin = dp(4) }
        return r
    }

    /** Mosaico vazio (cor lisa, cantos retos) que escurece ao tocar. */
    private fun tileBox(color: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        isClickable = true
        setOnClickListener { onClick() }
        setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN -> { v.alpha = 0.7f; v.scaleX = 0.97f; v.scaleY = 0.97f }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; v.scaleX = 1f; v.scaleY = 1f }
            }
            false
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.WHITE)
        maxLines = 1
    }

    /** Mosaico com um ícone grande ao centro e o nome em baixo à esquerda. */
    private fun iconTile(color: Int, icon: String, name: String, onClick: () -> Unit) = tileBox(color, onClick).apply {
        addView(TextView(this@MainActivity).apply {
            text = icon
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(16, 56, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(label(name))
    }

    private fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setBackgroundColor(tBar)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { rightMargin = dp(4) }
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
