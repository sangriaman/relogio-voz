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

/** Acesso à API do Home Assistant (chamar fora da thread principal). */
object HA {
    data class Entity(val id: String, val name: String, val state: String) {
        val domain get() = id.substringBefore('.')
        val isOn get() = state == "on"
    }

    private val domains = setOf("switch", "light", "fan", "input_boolean", "automation", "script")
    private val filler = setOf("a", "o", "as", "os", "da", "do", "das", "dos", "de", "e", "por", "favor", "luz")

    fun configured(ctx: Context) = Prefs.haToken(ctx).isNotBlank()

    fun states(ctx: Context): List<Entity> {
        val auth = mapOf("Authorization" to "Bearer ${Prefs.haToken(ctx)}")
        val arr = JSONArray(Http.get("${Prefs.haUrl(ctx)}/api/states", auth))
        val out = mutableListOf<Entity>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.getString("entity_id")
            if (id.substringBefore('.') !in domains) continue
            val name = s.optJSONObject("attributes")?.optString("friendly_name")?.ifBlank { null } ?: id
            out += Entity(id, name, s.optString("state"))
        }
        return out
    }

    /** O aparelho cujo nome melhor corresponde às palavras ditas. */
    fun best(entities: List<Entity>, spoken: String): Entity? {
        val wanted = Txt.tokens(spoken).filter { it !in filler }
        if (wanted.isEmpty()) return null
        var best: Entity? = null
        var bestScore = 0
        for (e in entities) {
            val words = Txt.tokens(e.name) + Txt.tokens(e.id.substringAfter('.').replace('_', ' '))
            val score = wanted.count { w -> words.any { it == w || it.startsWith(w) || (w.startsWith(it) && it.length > 3) } }
            val bonus = if (e.domain == "switch" || e.domain == "light") 1 else 0
            if (score > 0 && score * 2 + bonus > bestScore) {
                bestScore = score * 2 + bonus
                best = e
            }
        }
        return best
    }

    fun call(ctx: Context, e: Entity, service: String): Boolean {
        val svc = if (e.domain == "script") "turn_on" else service
        val body = JSONObject().put("entity_id", e.id).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${Prefs.haUrl(ctx)}/api/services/${e.domain}/$svc")
            .header("Authorization", "Bearer ${Prefs.haToken(ctx)}")
            .post(body)
            .build()
        Http.client.newCall(req).execute().use { return it.isSuccessful }
    }
}

/** "Liga a resistência" / "Desliga a tomada da sala" */
class HomeSkill : Skill {
    private val on = listOf("liga", "ligar", "acende", "acender", "ativa", "ativar")
    private val off = listOf("desliga", "desligar", "apaga", "apagar", "desativa", "desativar")

    override fun matches(n: String, t: List<String>) = t.isNotEmpty() && (t[0] in on || t[0] in off)

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        if (!HA.configured(ctx)) return Answer("Ainda não configuraste o Home Assistant nas definições.")
        val turnOn = t[0] in on
        val spoken = t.drop(1).joinToString(" ")
        val e = HA.best(HA.states(ctx), spoken)
            ?: return Answer("Não encontrei nenhum aparelho chamado $spoken.")
        if (!HA.call(ctx, e, if (turnOn) "turn_on" else "turn_off")) return Answer("O Home Assistant deu erro.")
        return Answer("${if (turnOn) "Liguei" else "Desliguei"} ${e.name}.")
    }
}
