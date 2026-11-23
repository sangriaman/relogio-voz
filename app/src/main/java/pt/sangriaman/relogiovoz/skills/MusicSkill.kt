package pt.sangriaman.relogiovoz.skills

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import pt.sangriaman.relogiovoz.Bus
import pt.sangriaman.relogiovoz.Http
import pt.sangriaman.relogiovoz.PtNumbers
import pt.sangriaman.relogiovoz.Txt
import java.net.URLEncoder
import kotlin.math.roundToInt

/**
 * Gestor unificado de áudio (rádios e YouTube) com uma única instância reutilizável de ExoPlayer,
 * fallback automático de streams e fila nativa baseada em relatedItems do YouTube.
 */
object Radio {
    private const val TAG = "Radio"
    private const val STREAM_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private enum class PlayMode { NONE, RADIO, YOUTUBE }

    data class Track(
        val pageUrl: String,
        val title: String,
        val artist: String?,
        val audioUrl: String? = null
    ) {
        fun label(): String = "🎵 $title${if (!artist.isNullOrBlank()) " — $artist" else ""}"
    }

    private val main = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var appCtx: Context? = null
    private var player: ExoPlayer? = null
    @Volatile
    private var mode = PlayMode.NONE
    private var ducked = false

    // Estado de rádio (com fallback de múltiplos URLs)
    private var radioUrls: List<String> = emptyList()
    private var radioIndex = 0
    private var radioFallbackQuery: String? = null
    private var radioFallbackTried = false

    // Fila do YouTube (relatedItems)
    private val ytQueue = mutableListOf<Track>()
    private var ytIndex = -1
    private var ytConsecutiveErrors = 0
    private var loadJob: Job? = null

    @Volatile
    private var active = false

    @Volatile
    var stationName: String? = null
        private set

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> ytConsecutiveErrors = 0
                Player.STATE_ENDED -> {
                    if (mode == PlayMode.YOUTUBE && active) {
                        val ctx = appCtx
                        if (ctx != null && ytIndex + 1 < ytQueue.size) {
                            ytIndex++
                            playYoutubeIndex(ctx, ytIndex)
                        } else {
                            clearPlaybackState()
                            Bus.nowPlaying.value = ""
                        }
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Erro de reprodução (${error.errorCodeName}): ${error.message}", error)
            when (mode) {
                PlayMode.RADIO -> handleRadioError()
                PlayMode.YOUTUBE -> handleYoutubeError()
                PlayMode.NONE -> {}
            }
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun ensurePlayer(ctx: Context): ExoPlayer {
        val app = ctx.applicationContext
        appCtx = app
        player?.let { return it }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(app)
            .setDataSourceFactory(httpFactory)
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(playerListener)
                it.volume = if (ducked) 0.15f else 1f
                player = it
            }
    }

    private fun playUrlNow(ctx: Context, url: String) {
        val p = ensurePlayer(ctx)
        p.volume = if (ducked) 0.15f else 1f
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    fun play(ctx: Context, url: String, name: String) {
        playRadio(ctx, listOf(url), name)
    }

    fun playRadio(ctx: Context, urls: List<String>, name: String, fallbackQuery: String? = null) {
        val clean = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (clean.isEmpty()) return
        runOnMain {
            loadJob?.cancel()
            loadJob = null
            ytQueue.clear()
            ytIndex = -1
            ytConsecutiveErrors = 0

            mode = PlayMode.RADIO
            radioUrls = clean
            radioIndex = 0
            radioFallbackQuery = fallbackQuery
            radioFallbackTried = false
            stationName = name
            active = true

            Bus.nowPlaying.value = "📻 $name"
            playUrlNow(ctx, clean[0])
        }
    }

    fun playYoutube(ctx: Context, first: Track, related: List<Track>) {
        val audio = first.audioUrl ?: return
        runOnMain {
            loadJob?.cancel()
            loadJob = null
            radioUrls = emptyList()
            radioIndex = 0
            radioFallbackQuery = null
            radioFallbackTried = false

            ytQueue.clear()
            ytQueue += first
            val seen = mutableSetOf(first.pageUrl)
            for (r in related) {
                if (seen.add(r.pageUrl)) {
                    ytQueue += r
                    if (ytQueue.size >= 25) break
                }
            }
            ytIndex = 0
            ytConsecutiveErrors = 0
            mode = PlayMode.YOUTUBE
            stationName = first.title
            active = true

            Bus.nowPlaying.value = first.label()
            playUrlNow(ctx, audio)
        }
    }

    fun hasQueue(): Boolean = mode == PlayMode.YOUTUBE && ytQueue.isNotEmpty()

    fun next(ctx: Context): Boolean {
        if (!hasQueue()) return false
        runOnMain {
            if (mode != PlayMode.YOUTUBE || ytQueue.isEmpty()) return@runOnMain
            if (ytIndex + 1 < ytQueue.size) {
                ytIndex++
                ytConsecutiveErrors = 0
                playYoutubeIndex(ctx, ytIndex)
            } else {
                stop()
                Bus.nowPlaying.value = ""
            }
        }
        return true
    }

    fun previous(ctx: Context): Boolean {
        if (!hasQueue()) return false
        runOnMain {
            if (mode != PlayMode.YOUTUBE || ytQueue.isEmpty()) return@runOnMain
            if (ytIndex > 0) {
                ytIndex--
                ytConsecutiveErrors = 0
                playYoutubeIndex(ctx, ytIndex)
            } else {
                player?.seekTo(0)
                player?.playWhenReady = true
            }
        }
        return true
    }

    private fun playYoutubeIndex(ctx: Context, index: Int, forceResolve: Boolean = false) {
        if (index !in ytQueue.indices) return
        val track = ytQueue[index]
        stationName = track.title
        active = true
        if (!forceResolve && !track.audioUrl.isNullOrBlank()) {
            Bus.nowPlaying.value = track.label()
            playUrlNow(ctx, track.audioUrl)
            return
        }
        Bus.nowPlaying.value = "🎵 A carregar ${track.title}…"
        loadJob?.cancel()
        loadJob = ioScope.launch {
            val resolved = try {
                MusicSkill.resolveTrack(track.pageUrl)
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao resolver faixa seguinte: ${track.title}", e)
                null
            }
            main.post {
                if (mode != PlayMode.YOUTUBE || ytIndex != index || !active) return@post
                if (resolved != null && !resolved.first.audioUrl.isNullOrBlank()) {
                    ytQueue[index] = resolved.first
                    if (ytQueue.size < 30) {
                        val seen = ytQueue.mapTo(mutableSetOf()) { it.pageUrl }
                        for (rel in resolved.second) {
                            if (seen.add(rel.pageUrl)) {
                                ytQueue += rel
                                if (ytQueue.size >= 30) break
                            }
                        }
                    }
                    stationName = resolved.first.title
                    Bus.nowPlaying.value = resolved.first.label()
                    playUrlNow(ctx, resolved.first.audioUrl!!)
                } else {
                    handleYoutubeError()
                }
            }
        }
    }

    private fun handleRadioError() {
        val ctx = appCtx
        if (ctx != null && radioIndex + 1 < radioUrls.size) {
            radioIndex++
            Log.i(TAG, "A tentar próximo stream da rádio (${radioIndex + 1}/${radioUrls.size})")
            playUrlNow(ctx, radioUrls[radioIndex])
            return
        }
        val query = radioFallbackQuery
        if (ctx != null && !radioFallbackTried && !query.isNullOrBlank()) {
            radioFallbackTried = true
            val currentName = stationName ?: query
            loadJob?.cancel()
            loadJob = ioScope.launch {
                val fallback = try {
                    MusicSkill.searchRadioBrowser(query)
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback radio-browser falhou para '$query'", e)
                    null
                }
                main.post {
                    if (mode != PlayMode.RADIO || !active) return@post
                    val extra = fallback?.second?.filterNot { it in radioUrls }.orEmpty()
                    if (extra.isNotEmpty()) {
                        radioUrls = extra
                        radioIndex = 0
                        stationName = currentName
                        playUrlNow(ctx, extra[0])
                    } else {
                        clearPlaybackState()
                        Bus.nowPlaying.value = ""
                        Bus.status.value = "Não consegui reproduzir esta rádio."
                    }
                }
            }
            return
        }
        clearPlaybackState()
        Bus.nowPlaying.value = ""
        Bus.status.value = "Não consegui reproduzir esta rádio."
    }

    private fun handleYoutubeError() {
        val ctx = appCtx
        ytConsecutiveErrors++
        if (ctx != null && ytConsecutiveErrors <= 3 && ytIndex + 1 < ytQueue.size) {
            ytIndex++
            playYoutubeIndex(ctx, ytIndex)
        } else {
            clearPlaybackState()
            Bus.nowPlaying.value = ""
            Bus.status.value = "Não consegui reproduzir a música."
        }
    }

    private fun clearPlaybackState() {
        loadJob?.cancel()
        loadJob = null
        player?.stop()
        player?.clearMediaItems()
        mode = PlayMode.NONE
        radioUrls = emptyList()
        radioIndex = 0
        radioFallbackQuery = null
        radioFallbackTried = false
        ytQueue.clear()
        ytIndex = -1
        ytConsecutiveErrors = 0
        stationName = null
        active = false
    }

    fun isPlaying(): Boolean = active

    fun stop() {
        active = false
        stationName = null
        runOnMain { clearPlaybackState() }
    }

    fun release() {
        active = false
        stationName = null
        runOnMain {
            clearPlaybackState()
            player?.removeListener(playerListener)
            player?.release()
            player = null
        }
    }

    fun setPaused(paused: Boolean) {
        runOnMain {
            if (active) player?.playWhenReady = !paused
        }
    }

    /** Baixa o som enquanto o Tricky ouve ou fala. */
    fun duck(on: Boolean) {
        runOnMain {
            ducked = on
            player?.volume = if (on) 0.15f else 1f
        }
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
        "quero ouvir", "poe a tocar", "poe me", "poe", "toca me", "toca", "tocar", "ouvir", "reproduz",
        "liga a radio", "liga o radio"
    )

    override fun score(n: String, t: List<String>): Int {
        if (t.isEmpty() || "volume" in t) return 0
        if (n.startsWith("liga a radio ") || n.startsWith("liga o radio ") || n.startsWith("radio ") ||
            n.startsWith("poe a radio ") || n.startsWith("toca a radio ")
        ) return 90
        if (triggers.any { n.startsWith("$it ") }) return 85
        return 0
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        var q = n
        for (tr in triggers) if (q.startsWith("$tr ")) { q = q.removePrefix("$tr "); break }
        q = q.replace(LEADING_ARTICLE, "")
            .replace(MUSIC_OF_PREFIX, "")
            .replace(MUSIC_PREFIX, "")
            .trim()
        if (q.isBlank()) return Answer("O que queres ouvir?")

        if (q.startsWith("radio") || n.contains("radio")) {
            val name = q.removePrefix("radio").trim().let { digits(it) }
            return radio(ctx, name)
        }
        return youtube(ctx, q)
    }

    /** "antena um" -> "antena 1", "m oitenta" -> "m 80" */
    private fun digits(s: String) = s.split(" ").joinToString(" ") {
        when (it) {
            "um" -> "1"
            "dois" -> "2"
            "tres" -> "3"
            "oitenta" -> "80"
            else -> it
        }
    }

    private fun radio(ctx: Context, name: String): Answer {
        if (name.isBlank()) return Answer("Que rádio queres ouvir?")
        val normalized = Txt.norm(name)

        // 1.º: arranque imediato com mapa local das principais rádios PT
        val preset = findLocalRadio(normalized)
        if (preset != null) {
            return Answer("Vou ligar a rádio!", display = "📻 ${preset.displayName}") {
                sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
                Radio.playRadio(ctx, preset.urls, preset.displayName, fallbackQuery = preset.fallbackQuery)
            }
        }

        // 2.º: fallback na API radio-browser.info (limit=5, testa o próximo em caso de falha)
        val result = searchRadioBrowser(name)
            ?: return Answer("Não consegui contactar o serviço de rádios.")
        if (result.second.isEmpty()) return Answer("Não encontrei essa rádio.")

        val (stName, urls) = result
        return Answer("Vou ligar a rádio!", display = "📻 $stName") {
            sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            Radio.playRadio(ctx, urls, stName)
        }
    }

    private fun youtube(ctx: Context, q: String): Answer {
        ensureNewPipe()
        val yt = ServiceList.YouTube
        val candidates = mutableListOf<StreamInfoItem>()
        for (filter in listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.VIDEOS)) {
            try {
                val ex = yt.getSearchExtractor(q, listOf(filter), "")
                ex.fetchPage()
                val found = ex.initialPage.items
                    .filterIsInstance<StreamInfoItem>()
                    .filter { it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.AUDIO_STREAM }
                candidates += found.take(3)
                if (candidates.isNotEmpty()) break
            } catch (e: Exception) {
                Log.w(TAG, "Pesquisa YouTube falhou ($filter) para '$q'", e)
            }
        }
        if (candidates.isEmpty()) return Answer("Não encontrei essa música.")

        // Tenta extrair o stream de áudio nativo (até 3 resultados)
        for (item in candidates.take(3)) {
            val id = YT_ID_REGEX.find(item.url)?.groupValues?.get(1)
            val pageUrl = if (id != null) "https://www.youtube.com/watch?v=$id" else item.url
            val resolved = try {
                resolveTrack(pageUrl)
            } catch (e: Exception) {
                Log.w(TAG, "StreamInfo falhou para $pageUrl", e)
                null
            }
            if (resolved != null) {
                val (track, related) = resolved
                return Answer("Vou tocar música!", display = track.label()) {
                    Radio.playYoutube(ctx, track, related)
                }
            }
        }
        return Answer("Não consegui reproduzir essa música.")
    }

    companion object {
        private const val TAG = "MusicSkill"
        private val LEADING_ARTICLE = Regex("^(a|o|as|os|um|uma) ")
        private val MUSIC_OF_PREFIX = Regex("^(musica|musicas|cancao|cancoes) (de|do|da|dos|das) ")
        private val MUSIC_PREFIX = Regex("^(musica|cancao) ")
        private val YT_ID_REGEX = Regex("[?&]v=([A-Za-z0-9_-]{11})")

        private data class RadioPreset(
            val displayName: String,
            val aliases: Set<String>,
            val urls: List<String>,
            val fallbackQuery: String
        )

        private val PT_RADIOS = listOf(
            RadioPreset(
                displayName = "Rádio Comercial",
                aliases = setOf("comercial", "radio comercial"),
                urls = listOf(
                    "https://stream-icy.bauermedia.pt/comercial.aac",
                    "https://stream-icy.bauermedia.pt/comercial.mp3",
                    "https://stream-hls.bauermedia.pt/comercial.aac/playlist.m3u8"
                ),
                fallbackQuery = "Radio Comercial"
            ),
            RadioPreset(
                displayName = "RFM",
                aliases = setOf("rfm", "radio rfm"),
                urls = listOf(
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/RFMAAC.aac",
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/RFM.mp3"
                ),
                fallbackQuery = "RFM"
            ),
            RadioPreset(
                displayName = "M80",
                aliases = setOf("m80", "m 80", "radio m80", "m80 radio"),
                urls = listOf(
                    "https://stream-icy.bauermedia.pt/m80.aac",
                    "https://stream-icy.bauermedia.pt/m80.mp3",
                    "https://stream-hls.bauermedia.pt/m80.aac/playlist.m3u8"
                ),
                fallbackQuery = "M80"
            ),
            RadioPreset(
                displayName = "Antena 1",
                aliases = setOf("antena 1", "antena1", "rtp antena 1"),
                urls = listOf(
                    "https://radiocast.rtp.pt/antena180a.mp3",
                    "https://streaming-live.rtp.pt/liveradio/antena180a/playlist.m3u8"
                ),
                fallbackQuery = "Antena 1"
            ),
            RadioPreset(
                displayName = "Antena 2",
                aliases = setOf("antena 2", "antena2", "rtp antena 2"),
                urls = listOf(
                    "https://radiocast.rtp.pt/antena280a.mp3",
                    "https://streaming-live.rtp.pt/liveradio/antena280a/playlist.m3u8"
                ),
                fallbackQuery = "Antena 2"
            ),
            RadioPreset(
                displayName = "Antena 3",
                aliases = setOf("antena 3", "antena3", "rtp antena 3"),
                urls = listOf(
                    "https://radiocast.rtp.pt/antena380a.mp3",
                    "https://streaming-live.rtp.pt/liveradio/antena380a/playlist.m3u8"
                ),
                fallbackQuery = "Antena 3"
            ),
            RadioPreset(
                displayName = "Rádio Renascença",
                aliases = setOf("renascenca", "radio renascenca", "rr"),
                urls = listOf(
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_RENASCENCAAAC.aac",
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/RADIO_RENASCENCA.mp3"
                ),
                fallbackQuery = "Renascenca"
            ),
            RadioPreset(
                displayName = "Mega Hits",
                aliases = setOf("mega hits", "megahits", "mega hit"),
                urls = listOf(
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/MEGA_HITSAAC.aac",
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/MEGA_HITS.mp3"
                ),
                fallbackQuery = "Mega Hits"
            ),
            RadioPreset(
                displayName = "Rádio Observador",
                aliases = setOf("observador", "radio observador"),
                urls = listOf(
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/OBSERVADORAAC_SC",
                    "https://playerservices.streamtheworld.com/api/livestream-redirect/OBSERVADORAAC.aac"
                ),
                fallbackQuery = "Observador"
            ),
            RadioPreset(
                displayName = "Cidade FM",
                aliases = setOf("cidade", "cidade fm", "radio cidade"),
                urls = listOf(
                    "https://stream-icy.bauermedia.pt/cidade.aac",
                    "https://stream-hls.bauermedia.pt/cidade.aac/playlist.m3u8"
                ),
                fallbackQuery = "Cidade FM"
            )
        )

        private fun findLocalRadio(normalized: String): RadioPreset? {
            PT_RADIOS.firstOrNull { normalized in it.aliases }?.let { return it }
            return PT_RADIOS.firstOrNull { p -> p.aliases.any { normalized.contains(it) } }
        }

        /** Pesquisa na API radio-browser.info (limit=5). Devolve null se os servidores falharem. */
        internal fun searchRadioBrowser(name: String): Pair<String, List<String>>? {
            val enc = URLEncoder.encode(name, "UTF-8")
            val servers = listOf("de1", "nl1", "at1")
            var arr: JSONArray? = null
            for (s in servers) {
                try {
                    val pt = JSONArray(
                        Http.get("https://$s.api.radio-browser.info/json/stations/search?name=$enc&countrycode=PT&hidebroken=true&order=votes&reverse=true&limit=5")
                    )
                    arr = if (pt.length() > 0) pt else JSONArray(
                        Http.get("https://$s.api.radio-browser.info/json/stations/search?name=$enc&hidebroken=true&order=votes&reverse=true&limit=5")
                    )
                    break
                } catch (_: Exception) {
                }
            }
            if (arr == null) return null
            if (arr.length() == 0) return name to emptyList()
            val firstName = arr.getJSONObject(0).optString("name", name).trim().ifBlank { name }
            val urls = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val st = arr.getJSONObject(i)
                val u = st.optString("url_resolved").ifBlank { st.optString("url") }.trim()
                if (u.isNotEmpty() && u !in urls) urls += u
            }
            return firstName to urls
        }

        /** Resolve o melhor stream de áudio e os itens relacionados de um vídeo do YouTube. */
        internal fun resolveTrack(pageUrl: String): Pair<Radio.Track, List<Radio.Track>>? {
            ensureNewPipe()
            val info = StreamInfo.getInfo(ServiceList.YouTube, pageUrl)
            val audioUrl = pickBestAudioUrl(info) ?: return null
            val artist = info.uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() }
            val title = info.name?.ifBlank { null } ?: "Música"
            val track = Radio.Track(pageUrl = info.url ?: pageUrl, title = title, artist = artist, audioUrl = audioUrl)
            val related = info.relatedItems
                .filterIsInstance<StreamInfoItem>()
                .filter { it.streamType == StreamType.VIDEO_STREAM || it.streamType == StreamType.AUDIO_STREAM }
                .mapNotNull { item ->
                    val u = item.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val who = item.uploaderName?.removeSuffix(" - Topic")?.takeIf { it.isNotBlank() }
                    Radio.Track(pageUrl = u, title = item.name ?: "Música", artist = who, audioUrl = null)
                }
                .take(25)
            return track to related
        }

        private fun pickBestAudioUrl(info: StreamInfo): String? {
            val best: AudioStream? = info.audioStreams
                .filter { it.isUrl && streamUrl(it).isNotBlank() }
                .maxByOrNull { streamScore(it) }
            if (best != null) return streamUrl(best)
            return info.hlsUrl?.takeIf { it.isNotBlank() }
        }

        private fun streamUrl(s: AudioStream): String = s.content

        private fun streamScore(s: AudioStream): Int {
            var score = 0
            if (s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP) score += 1000
            if (s.audioTrackType == null || s.audioTrackType == AudioTrackType.ORIGINAL) score += 500
            score += when (s.format) {
                MediaFormat.M4A -> 200
                MediaFormat.WEBMA_OPUS, MediaFormat.OPUS -> 180
                MediaFormat.WEBMA -> 150
                MediaFormat.MP3, MediaFormat.OGG -> 120
                else -> 50
            }
            val br = if (s.averageBitrate > 0) s.averageBitrate else s.bitrate
            score += br.coerceIn(0, 256)
            return score
        }

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
    when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> if (Radio.next(ctx)) return
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (Radio.previous(ctx)) return
    }
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
}

/** "Pára", "pausa", "continua", "próxima", "anterior", "mais alto", "volume a 50 por cento"… */
class MediaSkill : Skill {
    private val directStopVerbs = setOf("stop", "silencio", "chega", "cala")
    private val paraMediaPhrases = setOf(
        "a musica", "musica", "a radio", "radio", "o radio", "isso", "tudo", "o som", "som",
        "de tocar", "o audio", "audio", "a reproducao", "reproducao", "a cancao", "cancao"
    )
    private val paraTrailingPolite = setOf("por", "favor", "se", "faz", "ja", "ai", "la")

    private fun isValidPara(t: List<String>): Boolean {
        if (t.isEmpty() || t[0] != "para") return false
        if (t.size == 1) return true
        if (t.size > 6) return false
        val rest = t.drop(1).toMutableList()
        while (rest.isNotEmpty() && rest.last() in paraTrailingPolite) {
            rest.removeAt(rest.lastIndex)
        }
        return rest.isEmpty() || rest.joinToString(" ") in paraMediaPhrases
    }

    private fun isVolumeCommand(n: String, t: List<String>): Boolean {
        if (t.size > 10) return false
        if ("volume" in t) return true
        if (n.contains("mais alto") || n.contains("mais baixo")) return true
        if (t.hasAny("aumenta", "aumentar", "sobe", "subir", "baixa", "baixar", "diminui", "diminuir") &&
            t.hasAny("som", "volume", "musica", "radio")
        ) return true
        if (t.hasAny("som") && t.hasAny("maximo", "minimo")) return true
        return false
    }

    override fun score(n: String, t: List<String>): Int {
        if (t.isEmpty()) return 0
        if (isVolumeCommand(n, t)) return 95
        if (t[0] == "para") return if (isValidPara(t)) 95 else 0
        if (t.size > 5) return 0
        if (t[0] in directStopVerbs || t[0] in setOf("pausa", "continua", "retoma", "proxima", "seguinte", "salta", "anterior")) {
            return 95
        }
        if (n == "musica seguinte" || n == "musica anterior" || n == "proxima musica" || n == "cala te") {
            return 95
        }
        return 0
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Controlo de volume (percentagem, máximo, mínimo, subir, descer)
        if (isVolumeCommand(n, t)) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (n.contains("maximo") || n.contains("maxima") || n.contains("no topo")) {
                return Answer("") {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
                }
            }
            if (n.contains("minimo") || n.contains("minima")) {
                val minTarget = if (max > 0) 1 else 0
                return Answer("") {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, minTarget, AudioManager.FLAG_SHOW_UI)
                }
            }
            if ("volume" in t) {
                val volIdx = t.indexOf("volume")
                val num = PtNumbers.first(t, volIdx + 1) ?: PtNumbers.first(t)
                if (num != null) {
                    val pct = num.first.coerceIn(0, 100)
                    val target = (max * pct / 100f).roundToInt().coerceIn(0, max)
                    return Answer("") {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    }
                }
            }
            if (n.contains("mais alto") || t.hasAny("aumenta", "aumentar", "sobe", "subir")) {
                return Answer("") {
                    repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                }
            }
            if (n.contains("mais baixo") || t.hasAny("baixa", "baixar", "diminui", "diminuir")) {
                return Answer("") {
                    repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                }
            }
            return Answer("Diz, por exemplo, volume a cinquenta por cento, mais alto ou mais baixo.")
        }

        return when {
            t[0] == "pausa" -> Answer("") {
                if (Radio.isPlaying()) Radio.setPaused(true) else sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
            t[0] in setOf("continua", "retoma") -> Answer("") {
                if (Radio.isPlaying()) Radio.setPaused(false) else sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            t[0] in setOf("proxima", "seguinte", "salta") || n == "musica seguinte" || n == "proxima musica" -> Answer("") {
                if (!Radio.next(ctx)) sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
            }
            t[0] == "anterior" || n == "musica anterior" -> Answer("") {
                if (!Radio.previous(ctx)) sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            }
            else -> Answer("") {
                Radio.stop()
                Bus.nowPlaying.value = ""
                sendMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        }
    }
}
