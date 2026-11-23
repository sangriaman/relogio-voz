package pt.sangriaman.relogiovoz

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Voz feita de gravações: frases e palavras geradas com a voz Piper "tugão" (pt-PT)
 * e guardadas em assets/voz. Se uma resposta for toda composta por bocados gravados,
 * junta-os num único WAV; se faltar algum, devolve null e usa-se a voz do sistema.
 */
object ClipVoice {
    private const val DIR = "voz"
    private const val RATE = 16000
    private const val FADE_SAMPLES = 64 // ~4 ms a 16 kHz
    private val SEGMENT_SPLIT = Regex("[.,!?:;]+")

    @Volatile private var clips: Map<String, String>? = null
    @Volatile private var maxWords = 1

    private fun load(ctx: Context): Map<String, String> {
        clips?.let { return it }
        synchronized(this) {
            clips?.let { return it }
            val names = ctx.assets.list(DIR)?.filter { it.endsWith(".wav") } ?: emptyList()
            val m = names.associate { it.removeSuffix(".wav").replace('_', ' ') to "$DIR/$it" }
            maxWords = m.keys.maxOfOrNull { it.split(' ').size } ?: 1
            clips = m
            return m
        }
    }

    fun available(ctx: Context) = load(ctx).isNotEmpty()

    /** Constrói o ficheiro com a resposta, ou null se não houver gravações para tudo. */
    fun build(ctx: Context, text: String): File? {
        val map = load(ctx)
        if (map.isEmpty()) return null
        // frase completa gravada de uma vez (soa mais natural)
        map[Txt.tokens(text).joinToString(" ")]?.let { whole ->
            val out = File(ctx.cacheDir, "fala-gravada.wav")
            writeWav(out, applyFade(readPcm(ctx, whole)))
            return out
        }
        val segments = text.split(SEGMENT_SPLIT).map { Txt.tokens(it) }.filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null

        val parts = mutableListOf<Pair<String?, Int>>() // (asset, silêncio em ms depois)
        for ((si, seg) in segments.withIndex()) {
            var i = 0
            while (i < seg.size) {
                var found: String? = null
                var len = minOf(maxWords, seg.size - i)
                while (len > 0) {
                    val key = seg.subList(i, i + len).joinToString(" ")
                    found = map[key]
                    if (found != null) break
                    len--
                }
                if (found == null) return null
                i += len
                parts += found to (if (i < seg.size) 0 else if (si < segments.size - 1) 180 else 0)
            }
        }

        val pcm = ByteArrayOutputStream()
        for ((asset, gap) in parts) {
            if (asset != null) pcm.write(applyFade(readPcm(ctx, asset)))
            if (gap > 0) pcm.write(ByteArray(RATE * 2 * gap / 1000))
        }
        val out = File(ctx.cacheDir, "fala-gravada.wav")
        writeWav(out, pcm.toByteArray())
        return out
    }

    /** Aplica fade-in e fade-out suave (~4 ms) nas pontas de um clip PCM 16-bit LE mono. */
    private fun applyFade(pcm: ByteArray): ByteArray {
        val totalSamples = pcm.size / 2
        val fade = minOf(FADE_SAMPLES, totalSamples / 2)
        if (fade <= 0) return pcm
        for (i in 0 until fade) {
            val gain = i.toFloat() / fade
            // início (fade-in)
            val sIdx = i * 2
            val s0 = (pcm[sIdx].toInt() and 0xFF) or (pcm[sIdx + 1].toInt() shl 8)
            val v0 = (s0.toShort() * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[sIdx] = (v0 and 0xFF).toByte()
            pcm[sIdx + 1] = ((v0 ushr 8) and 0xFF).toByte()

            // fim (fade-out)
            val eIdx = (totalSamples - 1 - i) * 2
            val s1 = (pcm[eIdx].toInt() and 0xFF) or (pcm[eIdx + 1].toInt() shl 8)
            val v1 = (s1.toShort() * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[eIdx] = (v1 and 0xFF).toByte()
            pcm[eIdx + 1] = ((v1 ushr 8) and 0xFF).toByte()
        }
        return pcm
    }

    /** Lê só os dados de áudio de um WAV (procura o bloco "data"). */
    private fun readPcm(ctx: Context, asset: String): ByteArray {
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4, Charsets.US_ASCII)
            val size = bb.getInt(p + 4)
            if (id == "data") return bytes.copyOfRange(p + 8, minOf(bytes.size, p + 8 + size))
            p += 8 + size + (size and 1)
        }
        return ByteArray(0)
    }

    private fun writeWav(f: File, pcm: ByteArray) {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        FileOutputStream(f).use { it.write(h.array()); it.write(pcm) }
    }
}

/** Números por extenso em português de Portugal (0–100; acima disso, em algarismos). */
object Words {
    private val END_UM = Regex("\\bum$")
    private val END_DOIS = Regex("\\bdois$")
    private val units = listOf(
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove",
        "dez", "onze", "doze", "treze", "catorze", "quinze", "dezasseis", "dezassete", "dezoito", "dezanove"
    )
    private val tens = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")

    fun of(n: Int, feminine: Boolean = false): String {
        if (n < 0) return "menos " + of(-n, feminine)
        val s = when {
            n < 20 -> units[n]
            n < 100 -> tens[n / 10] + if (n % 10 != 0) " e " + units[n % 10] else ""
            n == 100 -> "cem"
            else -> n.toString()
        }
        if (!feminine) return s
        return s.replace(END_UM, "uma").replace(END_DOIS, "duas")
    }
}
