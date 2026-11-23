package pt.sangriaman.relogiovoz.skills

import android.content.Context
import org.json.JSONObject
import pt.sangriaman.relogiovoz.Http
import pt.sangriaman.relogiovoz.Prefs
import pt.sangriaman.relogiovoz.Txt
import pt.sangriaman.relogiovoz.Words
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/** Meteorologia através do Open-Meteo (gratuito, sem chave). */
class WeatherSkill : Skill {
    override fun score(n: String, t: List<String>): Int {
        // não colidir com perguntas sobre temporizadores ("quanto tempo falta")
        if (n.contains("quanto tempo") || n.contains("ha quanto tempo") || n.contains("tempo falta") ||
            t.hasAny("temporizador", "cronometro")
        ) return 0

        if (t.hasAny("meteorologia", "previsao", "chover", "chove", "chuva", "temperatura", "graus")) return 90
        if (n.contains("como esta o tempo") || n.contains("que tempo") || n.contains("qual o tempo") ||
            n.contains("qual e o tempo") || n.contains("tempo hoje") || n.contains("tempo amanha")
        ) return 85
        if (t.hasAny("calor", "frio")) {
            return if (t.hasAny("faz", "esta", "vai", "hoje", "amanha", "agora")) 80 else 60
        }
        if ("tempo" in t) {
            return if (LOCATION_REGEX.containsMatchIn(n) || t.hasAny("hoje", "amanha", "agora")) 80 else 50
        }
        return 0
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        val dayIndex = when {
            n.contains("depois de amanha") -> 2
            n.contains("amanha") -> 1
            else -> 0
        }
        val city = LOCATION_REGEX.find(n)?.groupValues?.get(2)
            ?.replace(TIME_NOISE_REGEX, "")
            ?.replace(MULTI_SPACE, " ")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: Prefs.city(ctx)

        val f = fetch(city)
            ?: return Answer("Não encontrei essa localidade.")
        return Answer(speak(f.name, f.json, dayIndex))
    }

    companion object {
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private val LOCATION_REGEX = Regex("\\b(em|no|na|nos|nas)\\s+([a-z ]+)")
        private val TIME_NOISE_REGEX =
            Regex("\\b(depois de amanha|fim de semana|esta semana|amanha|hoje|agora|manha|tarde|noite|madrugada|semana)\\b")
        private val MULTI_SPACE = Regex("\\s+")

        private data class GeoPlace(val name: String, val lat: Double, val lon: Double)
        private data class Cached<T>(val value: T, val timeMs: Long)

        private val geoCache = ConcurrentHashMap<String, Cached<GeoPlace>>()
        private val forecastCache = ConcurrentHashMap<String, Cached<Forecast>>()

        class Forecast(val name: String, val json: JSONObject)

        /** Previsão para uma localidade (chamar fora da thread principal). */
        fun fetch(city: String): Forecast? {
            val now = System.currentTimeMillis()
            val key = Txt.norm(city).ifBlank { return null }

            forecastCache[key]?.let { c ->
                if (now - c.timeMs < CACHE_TTL_MS) return c.value
            }

            val place = geoCache[key]?.takeIf { now - it.timeMs < CACHE_TTL_MS }?.value
                ?: resolvePlace(city)?.also { geoCache[key] = Cached(it, now) }
                ?: return null

            val w = JSONObject(
                Http.get(
                    "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                        "&current=temperature_2m,weather_code" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                        "&timezone=auto&forecast_days=3"
                )
            )
            return Forecast(place.name, w).also { forecastCache[key] = Cached(it, now) }
        }

        private fun resolvePlace(city: String): GeoPlace? {
            val geo = JSONObject(
                Http.get(
                    "https://geocoding-api.open-meteo.com/v1/search?count=5&language=pt&format=json&name=" +
                        URLEncoder.encode(city, "UTF-8")
                )
            )
            val results = geo.optJSONArray("results")
            if (results == null || results.length() == 0) return null
            var chosen = results.getJSONObject(0)
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                if (r.optString("country_code").equals("PT", ignoreCase = true)) {
                    chosen = r
                    break
                }
            }
            return GeoPlace(
                name = chosen.optString("name", city),
                lat = chosen.getDouble("latitude"),
                lon = chosen.getDouble("longitude")
            )
        }

        /** Linha curta para o widget do ecrã inicial. */
        fun widgetLine(f: Forecast): String {
            val cur = f.json.getJSONObject("current")
            val d = f.json.getJSONObject("daily")
            val temp = cur.getDouble("temperature_2m").roundToInt()
            val max = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
            val min = d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt()
            val rain = d.getJSONArray("precipitation_probability_max").optInt(0, 0)
            return "${icon(cur.getInt("weather_code"))} $temp° ${f.name} · ${describe(cur.getInt("weather_code"))}\n↑$max° ↓$min° · chuva $rain%"
        }

        fun icon(code: Int) = when (code) {
            0, 1 -> "☀️"
            2 -> "⛅"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..67, in 80..82 -> "🌧️"
            in 71..77, 85, 86 -> "❄️"
            in 95..99 -> "⛈️"
            else -> "🌡️"
        }

        fun speak(name: String, w: JSONObject, dayIndex: Int): String {
        val daily = w.getJSONObject("daily")
        val max = daily.getJSONArray("temperature_2m_max").getDouble(dayIndex).roundToInt()
        val min = daily.getJSONArray("temperature_2m_min").getDouble(dayIndex).roundToInt()
        val rain = daily.getJSONArray("precipitation_probability_max").optInt(dayIndex, 0)
        val code = daily.getJSONArray("weather_code").getInt(dayIndex)

        val sb = StringBuilder()
        if (dayIndex == 0) {
            val cur = w.getJSONObject("current")
            val temp = cur.getDouble("temperature_2m").roundToInt()
            sb.append("Em $name ${if (abs(temp) == 1) "está agora" else "estão agora"} ${Words.of(temp)} ${if (abs(temp) == 1) "grau" else "graus"}, ${describe(cur.getInt("weather_code"))}. ")
            sb.append("Hoje a máxima é de ${Words.of(max)} e a mínima de ${Words.of(min)}")
        } else {
            val day = if (dayIndex == 1) "Amanhã" else "Depois de amanhã"
            sb.append("$day em $name: ${describe(code)}. A máxima é de ${Words.of(max)} e a mínima de ${Words.of(min)}")
        }
        sb.append(
            when {
                rain >= 60 -> ". Com ${Words.of((rain + 2) / 5 * 5)} por cento de probabilidade de chuva. Leva o guarda-chuva."
                rain >= 20 -> ". Com ${Words.of((rain + 2) / 5 * 5)} por cento de probabilidade de chuva."
                else -> ". Não está prevista chuva."
            }
        )
        return sb.toString()
        }

        fun describe(code: Int) = when (code) {
        0 -> "céu limpo"
        1 -> "céu pouco nublado"
        2 -> "céu parcialmente nublado"
        3 -> "céu encoberto"
        45, 48 -> "nevoeiro"
        51, 53, 55 -> "chuviscos"
        56, 57 -> "chuviscos gelados"
        61 -> "chuva fraca"
        63 -> "chuva"
        65 -> "chuva forte"
        66, 67 -> "chuva gelada"
        71, 73, 75, 77 -> "neve"
        80 -> "aguaceiros fracos"
        81 -> "aguaceiros"
        82 -> "aguaceiros fortes"
        85, 86 -> "aguaceiros de neve"
        95 -> "trovoada"
        96, 99 -> "trovoada com granizo"
        else -> "tempo variável"
    }
    }
}
