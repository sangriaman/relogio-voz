package pt.sangriaman.relogiovoz.skills

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import pt.sangriaman.relogiovoz.Http
import java.net.URLEncoder

const val NEWPIPE = "org.schabi.newpipe"

/** Leitor das rádios (o YouTube é tocado pelo NewPipe). Usar só na thread principal. */
object Radio {
    private var player: ExoPlayer? = null
    var stationName: String? = null
        private set

    fun play(ctx: Context, url: String, name: String) {
        stop()
        val p = ExoPlayer.Builder(ctx.applicationContext).build()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.play()
        player = p
        stationName = name
    }

    fun isPlaying() = player != null

    fun stop() {
        player?.release()
        player = null
        stationName = null
    }

    fun setPaused(paused: Boolean) {
        player?.playWhenReady = !paused
    }

    /** Baixa o som enquanto o Manel ouve ou fala. */
    fun duck(on: Boolean) {
        player?.volume = if (on) 0.15f else 1f
    }
}

/** Liga o NewPipeExtractor à internet através do OkHttp. */
private class NpDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody(null)
            request.httpMethod() == "POST" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val b = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
            )
        request.headers().forEach { (k, values) ->
            b.removeHeader(k)
            values.forEach { b.addHeader(k, it) }
        }
        client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}

/** "Toca Xutos" / "Põe a Rádio Comercial" / "Quero ouvir Mariza" */
class MusicSkill : Skill {
    private val triggers = listOf(
        "quero ouvir", "poe a tocar", "poe me", "poe", "toca me", "toca", "tocar", "ouvir", "reproduz", "liga a radio", "liga o radio"
    )

    override fun matches(n: String, t: List<String>) =
        triggers.any { n.startsWith("$it ") } || n.startsWith("radio ")

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        var q = n
        for (tr in triggers) if (q.startsWith("$tr ")) { q = q.removePrefix("$tr "); break }
        q = q.replace(Regex("^(a|o|as|os|um|uma) "), "")
            .replace(Regex("^(musica|musicas|cancao|cancoes) (de|do|da|dos|das) "), "")
            .replace(Regex("^(musica|cancao) "), "")
            .trim()
        if (q.isBlank()) return Answer("O que queres ouvir?")

        if (q.startsWith("radio") || n.contains("radio")) {
            val name = q.removePrefix("radio").trim().let { digits(it) }
            return radio(ctx, name)
        }
        return youtube(ctx, q)
    }

    /** "antena um" -> "antena 1" */
    private fun digits(s: String) = s.split(" ").joinToString(" ") {
        when (it) { "um" -> "1"; "dois" -> "2"; "tres" -> "3"; else -> it }
    }

    private fun radio(ctx: Context, name: String): Answer {
        if (name.isBlank()) return Answer("Que rádio queres ouvir?")
        val enc = URLEncoder.encode(name, "UTF-8")
        val servers = listOf("de1", "nl1", "at1")
        var arr: JSONArray? = null
        for (s in servers) {
            try {
                val pt = JSONArray(Http.get("https://$s.api.radio-browser.info/json/stations/search?name=$enc&countrycode=PT&hidebroken=true&order=votes&reverse=true&limit=1"))
                arr = if (pt.length() > 0) pt else JSONArray(Http.get("https://$s.api.radio-browser.info/json/stations/search?name=$enc&hidebroken=true&order=votes&reverse=true&limit=1"))
                break
            } catch (_: Exception) {
            }
        }
        if (arr == null) return Answer("Não consegui contactar o serviço de rádios.")
        if (arr.length() == 0) return Answer("Não encontrei a rádio $name.")
        val st = arr.getJSONObject(0)
        val url = st.optString("url_resolved").ifBlank { st.optString("url") }
        val stName = st.optString("name", name).trim()
        return Answer("A ligar a $stName.") {
            sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE) // parar o NewPipe se estiver a tocar
            Radio.play(ctx, url, stName)
        }
    }

    private fun youtube(ctx: Context, q: String): Answer {
        val installed = try { ctx.packageManager.getPackageInfo(NEWPIPE, 0); true } catch (_: Exception) { false }
        if (!installed) return Answer("Para tocar música do YouTube tens de instalar o NewPipe.")

        ensureNewPipe()
        val yt = ServiceList.YouTube
        var item: StreamInfoItem? = null
        for (filter in listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.VIDEOS)) {
            try {
                val ex = yt.getSearchExtractor(q, listOf(filter), "")
                ex.fetchPage()
                item = ex.initialPage.items.filterIsInstance<StreamInfoItem>().firstOrNull()
                if (item != null) break
            } catch (_: Exception) {
            }
        }
        if (item == null) return Answer("Não encontrei $q no YouTube.")
        val id = Regex("[?&]v=([A-Za-z0-9_-]{11})").find(item.url)?.groupValues?.get(1)
        val url = if (id != null) "https://www.youtube.com/watch?v=$id" else item.url
        val who = item.uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() }
        val title = item.name
        return Answer("A tocar $title${if (who != null) ", de $who" else ""}.") {
            Radio.stop()
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage(NEWPIPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { ctx.startActivity(i) } catch (_: Exception) { }
        }
    }

    companion object {
        @Volatile private var npReady = false

        fun ensureNewPipe() {
            if (npReady) return
            synchronized(this) {
                if (!npReady) {
                    NewPipe.init(NpDownloader(Http.client), Localization("pt", "PT"), ContentCountry("PT"))
                    npReady = true
                }
            }
        }
    }
}

fun sendMediaKey(ctx: Context, code: Int) {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}

/** "Pára", "continua", "próxima", "mais alto"… */
class MediaSkill : Skill {
    override fun matches(n: String, t: List<String>): Boolean {
        if (t.isEmpty() || t.size > 5) return false
        return t[0] in setOf("para", "pausa", "stop", "silencio", "chega", "cala", "continua", "retoma", "proxima", "seguinte", "salta", "anterior") ||
            n.contains("mais alto") || n.contains("mais baixo") || n.contains("volume")
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            n.contains("mais alto") || n.contains("aumenta") || n.contains("sobe") -> Answer("") {
                repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
            }
            n.contains("mais baixo") || n.contains("baixa") || n.contains("diminui") -> Answer("") {
                repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            }
            t[0] in setOf("continua", "retoma") -> Answer("") {
                if (Radio.isPlaying()) Radio.setPaused(false) else sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            t[0] in setOf("proxima", "seguinte", "salta") -> Answer("") { sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT) }
            t[0] == "anterior" -> Answer("") { sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
            else -> Answer("") {
                Radio.stop()
                sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        }
    }
}
