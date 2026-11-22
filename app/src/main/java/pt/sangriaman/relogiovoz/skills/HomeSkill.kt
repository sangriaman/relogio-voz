package pt.sangriaman.relogiovoz.skills

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import pt.sangriaman.relogiovoz.Http
import pt.sangriaman.relogiovoz.Prefs
import pt.sangriaman.relogiovoz.Txt

/** "Liga a resistência" / "Desliga a tomada da sala" — via API do Home Assistant. */
class HomeSkill : Skill {
    private val on = listOf("liga", "ligar", "acende", "acender", "ativa", "ativar")
    private val off = listOf("desliga", "desligar", "apaga", "apagar", "desativa", "desativar")
    private val domains = setOf("switch", "light", "fan", "input_boolean", "automation", "script")
    private val filler = setOf("a", "o", "as", "os", "da", "do", "das", "dos", "de", "e", "por", "favor", "luz")

    override fun matches(n: String, t: List<String>) =
        t.isNotEmpty() && (t[0] in on || t[0] in off)

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        val token = Prefs.haToken(ctx)
        if (token.isBlank()) return Answer("Ainda não configuraste o Home Assistant nas definições do Manel.")
        val turnOn = t[0] in on
        val wanted = t.drop(1).filter { it !in filler }
        if (wanted.isEmpty()) return Answer("O que queres que ${if (turnOn) "ligue" else "desligue"}?")

        val base = Prefs.haUrl(ctx)
        val auth = mapOf("Authorization" to "Bearer $token")
        val states = JSONArray(Http.get("$base/api/states", auth))

        var bestId: String? = null
        var bestName = ""
        var bestScore = 0
        for (i in 0 until states.length()) {
            val s = states.getJSONObject(i)
            val id = s.getString("entity_id")
            val domain = id.substringBefore('.')
            if (domain !in domains) continue
            val name = s.optJSONObject("attributes")?.optString("friendly_name") ?: id
            val nameWords = Txt.tokens(name) + Txt.tokens(id.substringAfter('.').replace('_', ' '))
            val score = wanted.count { w -> nameWords.any { it == w || it.startsWith(w) || w.startsWith(it) && it.length > 3 } }
            // preferir interruptores/luzes a automações quando empatam
            val bonus = if (domain == "switch" || domain == "light") 1 else 0
            if (score > 0 && score * 2 + bonus > bestScore) {
                bestScore = score * 2 + bonus
                bestId = id
                bestName = name
            }
        }
        if (bestId == null) return Answer("Não encontrei nenhum aparelho chamado ${wanted.joinToString(" ")}.")

        val domain = bestId.substringBefore('.')
        val service = when (domain) {
            "script" -> "turn_on"
            else -> if (turnOn) "turn_on" else "turn_off"
        }
        val body = JSONObject().put("entity_id", bestId).toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$base/api/services/$domain/$service")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return Answer("O Home Assistant respondeu com erro ${r.code}.")
        }
        return Answer("${if (turnOn) "Liguei" else "Desliguei"} $bestName.")
    }
}
