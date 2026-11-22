package pt.sangriaman.relogiovoz.skills

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import pt.sangriaman.relogiovoz.Http
import java.net.URLEncoder

/**
 * "Pesquisa X" / "O que é X" / "Quem foi X":
 * lê o resumo da Wikipédia em português e, se não houver, abre a pesquisa no Google.
 * "Pesquisa no Google X" abre sempre o Google.
 */
class SearchSkill : Skill {
    private val triggers = listOf(
        "pesquisa no google", "procura no google", "pesquisa na internet", "procura na internet",
        "pesquisa sobre", "procura sobre", "pesquisar", "pesquisa", "procura",
        "o que e que e", "o que e", "o que sao", "quem e", "quem foi", "quem sao", "fala me de", "fala me sobre"
    )

    override fun matches(n: String, t: List<String>) = triggers.any { n.startsWith(it) || n.contains(" $it ") }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        var q = n
        for (tr in triggers) {
            val i = q.indexOf(tr)
            if (i >= 0) {
                q = q.substring(i + tr.length)
                break
            }
        }
        q = q.replace(Regex("^(o|a|os|as|um|uma) "), "").trim()
        if (q.isBlank()) return Answer("O que queres que pesquise?")

        val google = n.contains("google") || n.contains("internet")
        if (!google) {
            val summary = try { wikipedia(q) } catch (e: Exception) { null }
            if (summary != null) return Answer(summary)
        }
        return Answer("A abrir a pesquisa de $q no Google.") { openGoogle(ctx, q) }
    }

    private fun wikipedia(q: String): String? {
        val search = JSONObject(
            Http.get(
                "https://pt.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch=" +
                    URLEncoder.encode(q, "UTF-8")
            )
        )
        val hits = search.getJSONObject("query").getJSONArray("search")
        if (hits.length() == 0) return null
        val title = hits.getJSONObject(0).getString("title")
        val summary = JSONObject(
            Http.get(
                "https://pt.wikipedia.org/api/rest_v1/page/summary/" +
                    URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
            )
        )
        val extract = summary.optString("extract").trim()
        if (extract.isBlank()) return null
        // as duas primeiras frases chegam para ouvir
        val sentences = extract.split(Regex("(?<=[.!?])\\s+"))
        val text = sentences.take(2).joinToString(" ")
        return if (text.length > 400) text.substring(0, 400) + "…" else text
    }

    private fun openGoogle(ctx: Context, q: String) {
        val uri = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
