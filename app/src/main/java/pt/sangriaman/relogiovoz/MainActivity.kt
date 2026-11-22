package pt.sangriaman.relogiovoz

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
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
import kotlinx.coroutines.launch

/** Ecrã do relógio: horas, o que o Manel ouviu e respondeu, e botões. */
class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var overlayButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildUi())

        scope.launch { Bus.status.collect { status.text = it } }
        scope.launch { Bus.heard.collect { heard.text = if (it.isBlank()) "" else "Ouvi: «$it»" } }
        scope.launch { Bus.answer.collect { answer.text = it } }

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

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 12, 24, 12)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val clock = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            textSize = 64f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        val date = TextClock(this).apply {
            format24Hour = "EEEE, d 'de' MMMM"
            format12Hour = "EEEE, d 'de' MMMM"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        status = text(14f, Color.rgb(120, 200, 255))
        heard = text(14f, Color.GRAY)
        answer = text(16f, Color.WHITE).apply { maxLines = 4 }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(button("🎤 Falar") { AssistantService.instance?.listenForCommand() })
        buttons.addView(button("⚙ Definições") { startActivity(Intent(this, SettingsActivity::class.java)) })
        overlayButton = button("Permitir abrir apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        buttons.addView(overlayButton)

        root.addView(clock)
        root.addView(date)
        root.addView(status)
        root.addView(heard)
        root.addView(answer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(buttons)
        return root
    }

    private fun text(size: Float, color: Int) = TextView(this).apply {
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        setPadding(0, 6, 0, 6)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
}
