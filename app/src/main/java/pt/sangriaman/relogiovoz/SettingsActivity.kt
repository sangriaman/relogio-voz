package pt.sangriaman.relogiovoz

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** Palavra de ativação, cidade, Home Assistant e atualização da app. */
class SettingsActivity : Activity() {

    companion object {
        const val UPDATE_URL =
            "https://github.com/sangriaman/relogio-voz/releases/download/latest/relogio-voz.apk"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            setBackgroundColor(Color.rgb(0, 110, 112))
        }

        val wake = field(box, "Palavra de ativação (ex.: hey tricky)", Prefs.wakeWord(this))
        val city = field(box, "Cidade para a meteorologia", Prefs.city(this))
        val haUrl = field(box, "Endereço do Home Assistant", Prefs.haUrl(this))
        val pitch = field(box, "Voz de coelhinho: quão aguda (1 = normal, 1.45 = coelhinho, 0 = desligada)", if (Prefs.bunnyVoice(this)) Prefs.bunnyPitch(this).toString() else "0")
        val buttons = field(box, "Botões da casa no ecrã inicial (nomes separados por vírgulas)", Prefs.homeButtons(this).joinToString(", "))
        val haToken = field(box, "Token do Home Assistant (perfil → Segurança → Tokens de longa duração)", Prefs.haToken(this), password = true)

        box.addView(Button(this).apply {
            text = "Guardar"
            setOnClickListener {
                Prefs.save(this@SettingsActivity, "wake", wake.text.toString().trim())
                Prefs.save(this@SettingsActivity, "city", city.text.toString().trim())
                Prefs.save(this@SettingsActivity, "ha_url", haUrl.text.toString().trim())
                Prefs.save(this@SettingsActivity, "ha_token", haToken.text.toString().trim())
                Prefs.save(this@SettingsActivity, "home_buttons", buttons.text.toString().trim())
                val pv = pitch.text.toString().replace(',', '.').trim().toFloatOrNull() ?: 1.45f
                Prefs.save(this@SettingsActivity, "bunny", if (pv <= 0f) "nao" else "sim")
                if (pv > 0f) Prefs.save(this@SettingsActivity, "bunny_pitch", pv.toString())
                AssistantService.instance?.listenForWakeWord()
                Toast.makeText(this@SettingsActivity, "Guardado", Toast.LENGTH_SHORT).show()
                finish()
            }
        })

        val version = packageManager.getPackageInfo(packageName, 0).versionName
        box.addView(label("Versão instalada: $version"))
        box.addView(Button(this).apply {
            text = "Procurar atualização agora (é automática a cada 6 horas)"
            isAllCaps = false
            setOnClickListener { update(this) }
        })

        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun label(s: String) = TextView(this).apply {
        text = s
        setTextColor(Color.rgb(220, 250, 248))
        textSize = 13f
        setPadding(0, 14, 0, 2)
    }

    private fun field(box: LinearLayout, title: String, value: String, password: Boolean = false): EditText {
        box.addView(label(title))
        val e = EditText(this).apply {
            setText(value)
            setTextColor(Color.WHITE)
            isSingleLine = true
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(e)
        return e
    }

    private fun update(button: View) {
        button.isEnabled = false
        Toast.makeText(this, "A verificar…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val msg = withContext(Dispatchers.IO) { Updater.checkAndInstall(this@SettingsActivity) }
            Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            button.isEnabled = true
        }
    }
}
