package pt.sangriaman.relogiovoz

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/** Definições guardadas no relógio. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("manel", Context.MODE_PRIVATE)

    fun wakeWord(c: Context) = p(c).getString("wake", "hey tricky")!!.ifBlank { "hey tricky" }
    fun city(c: Context) = p(c).getString("city", "Lisboa")!!.ifBlank { "Lisboa" }
    fun haUrl(c: Context) = p(c).getString("ha_url", "http://192.168.1.109")!!.trimEnd('/')
    fun haToken(c: Context) = p(c).getString("ha_token", "")!!.trim()
    /** nomes dos aparelhos que aparecem como botões no ecrã inicial */
    fun homeButtons(c: Context) = p(c).getString("home_buttons", "resistência")!!
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    fun save(c: Context, key: String, value: String) {
        p(c).edit().putString(key, value).apply()
    }
}

/** Estado partilhado entre o serviço e o ecrã. */
object Bus {
    val status = MutableStateFlow("A iniciar…")
    val heard = MutableStateFlow("")
    val answer = MutableStateFlow("")
    /** estado do peluche: idle, listening, thinking, speaking */
    val mode = MutableStateFlow("idle")
    val nowPlaying = MutableStateFlow("")
}

object Http {
    const val UA = "RelogioVoz/1.0 (Mi Smart Clock; LineageOS)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** GET simples (chamar fora da thread principal). */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }
}

object Txt {
    /** minúsculas, sem acentos, sem pontuação, espaços simples */
    fun norm(s: String): String {
        val noAccents = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun tokens(s: String): List<String> = norm(s).split(" ").filter { it.isNotBlank() }
}

/** Converte números ditos em português ("vinte e cinco") em inteiros. */
object PtNumbers {
    private val words = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3,
        "quatro" to 4, "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9,
        "dez" to 10, "onze" to 11, "doze" to 12, "treze" to 13, "catorze" to 14,
        "quatorze" to 14, "quinze" to 15, "dezasseis" to 16, "dezesseis" to 16,
        "dezassete" to 17, "dezessete" to 17, "dezoito" to 18, "dezanove" to 19,
        "dezenove" to 19, "vinte" to 20, "trinta" to 30, "quarenta" to 40,
        "cinquenta" to 50, "sessenta" to 60, "setenta" to 70, "oitenta" to 80,
        "noventa" to 90, "cem" to 100, "cento" to 100, "duzentos" to 200
    )

    fun value(t: String): Int? = t.toIntOrNull() ?: words[t]

    /** Lê um número a partir de [start]. Devolve (valor, índice seguinte). */
    fun parseAt(tokens: List<String>, start: Int): Pair<Int, Int>? {
        var i = start
        var total = 0
        var found = false
        while (i < tokens.size) {
            val v = value(tokens[i])
            if (v != null && (!found || v < total)) {
                total += v
                found = true
                i++
                continue
            }
            if (tokens[i] == "e" && found && i + 1 < tokens.size) {
                val next = value(tokens[i + 1])
                val limit = if (total >= 100) 100 else 10
                if (next != null && total >= 20 && next < limit) {
                    i++
                    continue
                }
            }
            break
        }
        return if (found) Pair(total, i) else null
    }

    /** Primeiro número no texto: (valor, índice inicial, índice seguinte). */
    fun first(tokens: List<String>, from: Int = 0): Triple<Int, Int, Int>? {
        for (i in from until tokens.size) {
            if (value(tokens[i]) != null) {
                val r = parseAt(tokens, i) ?: continue
                return Triple(r.first, i, r.second)
            }
        }
        return null
    }
}
