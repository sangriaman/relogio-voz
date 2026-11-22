package pt.sangriaman.relogiovoz.skills

import android.content.Context
import org.json.JSONObject
import pt.sangriaman.relogiovoz.Http
import pt.sangriaman.relogiovoz.Prefs
import pt.sangriaman.relogiovoz.Words
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToInt

/** Meteorologia através do Open-Meteo (gratuito, sem chave). */
class WeatherSkill : Skill {
    override fun matches(n: String, t: List<String>) =
        t.hasAny("tempo", "meteorologia", "chover", "chove", "chuva", "temperatura", "previsao", "calor", "frio", "graus")

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        val dayIndex = when {
            n.contains("depois de amanha") -> 2
            n.contains("amanha") -> 1
            else -> 0
        }
        val city = Regex("\\bem ([a-z ]+)").find(n)?.groupValues?.get(1)
            ?.replace(Regex("\\b(hoje|amanha|depois de amanha|agora)\\b"), "")
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: Prefs.city(ctx)

        val f = fetch(city)
            ?: return Answer("Não encontrei essa localidade.")
        val name = f.name
        val w = f.json
        return Answer(speak(name, w, dayIndex))
    }

    companion object {
        class Forecast(val name: String, val json: JSONObject)

        /** Previsão para uma localidade (chamar fora da thread principal). */
        fun fetch(city: String): Forecast? {
        val geo = JSONObject(
            Http.get(
                "https://geocoding-api.open-meteo.com/v1/search?count=1&language=pt&format=json&name=" +
                    URLEncoder.encode(city, "UTF-8")
            )
        )
        val results = geo.optJSONArray("results")
        if (results == null || results.length() == 0) return null
        val place = results.getJSONObject(0)
        val name = place.optString("name", city)
        val lat = place.getDouble("latitude")
        val lon = place.getDouble("longitude")

        val w = JSONObject(
            Http.get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                    "&timezone=auto&forecast_days=3"
            )
        )
        return Forecast(name, w)
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
