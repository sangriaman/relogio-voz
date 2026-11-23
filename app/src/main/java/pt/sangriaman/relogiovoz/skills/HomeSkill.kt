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
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    data class Entity(val id: String, val name: String, val state: String) {
        val domain get() = id.substringBefore('.')
        val isOn get() = state == "on" || state == "open"
    }

    private val domains = setOf("switch", "light", "fan", "input_boolean", "automation", "script", "cover", "scene")
    private val filler = setOf("a", "o", "as", "os", "da", "do", "das", "dos", "de", "e", "por", "favor", "se", "faz", "no", "na", "nos", "nas")
    private val lightHints = setOf("luz", "luzes", "candeeiro", "lampada")

    private val cacheLock = Any()
    private var cachedKey: String = ""
    private var cachedAt: Long = 0L
    private var cachedEntities: List<Entity>? = null

    fun configured(ctx: Context) = Prefs.haUrl(ctx).isNotBlank() && Prefs.haToken(ctx).isNotBlank()

    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedAt = 0L
            cachedEntities = null
        }
    }

    fun states(ctx: Context): List<Entity> = states(ctx, forceRefresh = false)

    fun states(ctx: Context, forceRefresh: Boolean): List<Entity> {
        val url = Prefs.haUrl(ctx)
        val token = Prefs.haToken(ctx)
        val key = "$url|$token"
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(cacheLock) {
                val cached = cachedEntities
                if (cached != null && cachedKey == key && now - cachedAt < CACHE_TTL_MS) {
                    return cached
                }
            }
        }
        val auth = mapOf("Authorization" to "Bearer $token")
        val arr = JSONArray(Http.get("$url/api/states", auth))
        val out = mutableListOf<Entity>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.getString("entity_id")
            if (id.substringBefore('.') !in domains) continue
            val name = s.optJSONObject("attributes")?.optString("friendly_name")?.ifBlank { null } ?: id
            out += Entity(id, name, s.optString("state"))
        }
        synchronized(cacheLock) {
            cachedKey = key
            cachedAt = System.currentTimeMillis()
            cachedEntities = out
        }
        return out
    }

    /** O aparelho cujo nome melhor corresponde às palavras ditas. */
    fun best(entities: List<Entity>, spoken: String): Entity? = best(entities, spoken, emptySet())

    fun best(entities: List<Entity>, spoken: String, preferredDomains: Set<String>): Entity? {
        val cleaned = Txt.tokens(spoken).filter { it !in filler }
        if (cleaned.isEmpty()) return null
        val mentionsLight = cleaned.any { it in lightHints }
        // Não descartar "luz" se for a única palavra útil; caso contrário, usá-la como bónus para light
        val withoutLight = cleaned.filter { it != "luz" && it != "luzes" }
        val wanted = if (withoutLight.isNotEmpty()) withoutLight else cleaned
        val onlyLightWord = withoutLight.isEmpty() && mentionsLight

        var best: Entity? = null
        var bestScore = 0
        for (e in entities) {
            val words = Txt.tokens(e.name) + Txt.tokens(e.id.substringAfter('.').replace('_', ' '))
            val matches = wanted.count { w -> words.any { it == w || it.startsWith(w) || (w.startsWith(it) && it.length > 3) } }
            if (matches == 0 && !(onlyLightWord && e.domain == "light")) continue

            var bonus = 0
            if (mentionsLight && e.domain == "light") bonus += 3
            if (e.domain in preferredDomains) bonus += 3
            if (e.domain == "switch" || e.domain == "light") bonus += 1

            val total = matches * 4 + bonus
            if (total > bestScore) {
                bestScore = total
                best = e
            }
        }
        return best
    }

    fun call(ctx: Context, e: Entity, service: String): Boolean {
        val svc = when (e.domain) {
            "script", "scene" -> "turn_on"
            "cover" -> when (service) {
                "turn_on", "open_cover" -> "open_cover"
                "turn_off", "close_cover" -> "close_cover"
                else -> service
            }
            else -> service
        }
        val body = JSONObject().put("entity_id", e.id).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("${Prefs.haUrl(ctx)}/api/services/${e.domain}/$svc")
            .header("Authorization", "Bearer ${Prefs.haToken(ctx)}")
            .post(body)
            .build()
        return try {
            Http.client.newCall(req).execute().use { r ->
                invalidateCache()
                r.isSuccessful
            }
        } catch (ex: Exception) {
            invalidateCache()
            throw ex
        }
    }
}

/** "Liga a resistência" / "Podes apagar a luz da sala?" / "Abre o estore" / "Ativa o modo cinema" */
class HomeSkill : Skill {
    private enum class Action { ON, OFF, ACTIVATE, OPEN, CLOSE }

    private val onVerbs = setOf("liga", "ligar", "acende", "acender")
    private val offVerbs = setOf("desliga", "desligar", "apaga", "apagar", "desativa", "desativar")
    private val activateVerbs = setOf("ativa", "ativar")
    private val openVerbs = setOf("abre", "abrir")
    private val closeVerbs = setOf("fecha", "fechar")

    private val politePrefixes = setOf(
        "podes", "pode", "podias", "consegues", "por", "favor", "se", "faz",
        "quero", "queria", "vai", "entao", "agora", "me", "tricky", "ei", "hey"
    )

    private data class Command(val action: Action, val target: String)

    private fun parse(t: List<String>): Command? {
        if (t.isEmpty()) return null
        var idx = 0
        while (idx < t.size && idx < 4 && t[idx] in politePrefixes) idx++
        if (idx >= t.size) return null

        val verb = t[idx]
        val action = when (verb) {
            in onVerbs -> Action.ON
            in offVerbs -> Action.OFF
            in activateVerbs -> Action.ACTIVATE
            in openVerbs -> Action.OPEN
            in closeVerbs -> Action.CLOSE
            else -> return null
        }

        val rest = t.drop(idx + 1).toMutableList()
        // remover cortesia no fim ("por favor", "se faz favor")
        while (rest.isNotEmpty() && rest.last() in setOf("favor", "por", "faz", "se")) {
            rest.removeAt(rest.lastIndex)
        }
        if (rest.isEmpty()) return null
        // não interferir com comandos de rádio ou volume
        if (rest.any { it == "radio" || it == "volume" }) return null
        return Command(action, rest.joinToString(" "))
    }

    override fun score(n: String, t: List<String>): Int {
        val cmd = parse(t) ?: return 0
        return when (cmd.action) {
            Action.ON, Action.OFF, Action.ACTIVATE -> 85
            Action.OPEN, Action.CLOSE -> 80
        }
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        if (!HA.configured(ctx)) return Answer("Ainda não configuraste o Home Assistant nas definições.")
        val cmd = parse(t) ?: return Answer("Não percebi que aparelho queres controlar.")

        val preferred = when (cmd.action) {
            Action.OPEN, Action.CLOSE -> setOf("cover")
            Action.ACTIVATE -> setOf("scene", "script", "automation")
            Action.ON, Action.OFF -> emptySet()
        }

        var entities = HA.states(ctx)
        var e = HA.best(entities, cmd.target, preferred)
        if (e == null) {
            // refresh em caso de cache desatualizada
            entities = HA.states(ctx, forceRefresh = true)
            e = HA.best(entities, cmd.target, preferred)
        }
        if (e == null) return Answer("Não encontrei nenhum aparelho chamado ${cmd.target}.")

        val service = when (cmd.action) {
            Action.ON -> "turn_on"
            Action.OFF -> "turn_off"
            Action.ACTIVATE -> "turn_on"
            Action.OPEN -> if (e.domain == "cover") "open_cover" else "turn_on"
            Action.CLOSE -> if (e.domain == "cover") "close_cover" else "turn_off"
        }

        var ok = HA.call(ctx, e, service)
        if (!ok) {
            // tenta atualizar a lista de entidades e repetir uma vez
            val refreshed = HA.best(HA.states(ctx, forceRefresh = true), cmd.target, preferred)
            if (refreshed != null) {
                e = refreshed
                ok = HA.call(ctx, e, service)
            }
        }
        if (!ok) return Answer("O Home Assistant deu erro.")

        val past = when {
            e.domain == "cover" && (cmd.action == Action.OPEN || cmd.action == Action.ON) -> "Abri"
            e.domain == "cover" && (cmd.action == Action.CLOSE || cmd.action == Action.OFF) -> "Fechei"
            cmd.action == Action.ACTIVATE || e.domain == "scene" -> "Ativei"
            cmd.action == Action.OPEN -> "Abri"
            cmd.action == Action.CLOSE -> "Fechei"
            cmd.action == Action.ON -> "Liguei"
            else -> "Desliguei"
        }
        return Answer("$past ${e.name}.")
    }
}
