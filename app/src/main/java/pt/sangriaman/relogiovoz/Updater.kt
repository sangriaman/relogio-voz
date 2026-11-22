package pt.sangriaman.relogiovoz

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import okhttp3.Request
import org.json.JSONObject

/**
 * Atualização automática: vê no GitHub se há uma versão mais nova (build N > versionCode),
 * descarrega-a e instala-a. Como a app se atualiza a si própria, o Android 13 normalmente
 * deixa instalar sem perguntar; se mesmo assim pedir confirmação, aparece o ecrã de instalar.
 */
object Updater {
    private const val API = "https://api.github.com/repos/sangriaman/relogio-voz/releases/latest"
    private const val APK = "https://github.com/sangriaman/relogio-voz/releases/download/latest/relogio-voz.apk"

    @Volatile private var running = false

    private fun installedBuild(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    /** Número da build publicada, lido do título da release "… (build N)". */
    private fun latestBuild(): Long? {
        val json = JSONObject(Http.get(API, mapOf("Accept" to "application/vnd.github+json")))
        val name = json.optString("name")
        return Regex("build (\\d+)").find(name)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Verifica e instala se houver versão nova. Chamar fora da thread principal.
     * Devolve uma frase curta com o resultado.
     */
    fun checkAndInstall(ctx: Context, force: Boolean = false): String {
        if (running) return "Já estou a atualizar…"
        running = true
        try {
            val latest = latestBuild() ?: return "Não consegui ver a versão publicada."
            val current = installedBuild(ctx)
            if (!force && latest <= current) return "Já tens a versão mais recente (build $current)."

            Bus.status.value = "A descarregar a versão nova (build $latest)…"
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                val req = Request.Builder().url(APK).header("User-Agent", Http.UA).build()
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) {
                        session.abandon()
                        return "A descarga falhou (HTTP ${r.code})."
                    }
                    session.openWrite("relogio-voz.apk", 0, r.body!!.contentLength()).use { out ->
                        r.body!!.byteStream().copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(ctx, InstallReceiver::class.java)
                val sender = PendingIntent.getBroadcast(
                    ctx, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                Bus.status.value = "A instalar a versão nova… o Tricky vai reiniciar."
                session.commit(sender.intentSender)
            }
            return "A instalar a build $latest."
        } catch (e: Exception) {
            return "A atualização falhou: ${e.message}"
        } finally {
            running = false
        }
    }
}

/** Recebe o resultado da instalação; se o Android pedir confirmação, mostra-a. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { if (confirm != null) ctx.startActivity(confirm) } catch (_: Exception) { }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "erro desconhecido"
                Bus.status.value = "A atualização falhou: $msg"
            }
        }
    }
}

/** Depois de atualizada, a app volta a arrancar sozinha. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try { AssistantService.start(ctx) } catch (_: Exception) { }
        }
    }
}
