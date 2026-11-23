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
    private val explicitTriggers = listOf(
        "pesquisa no google", "procura no google", "pesquisa na internet", "procura na internet",
        "pesquisa sobre", "procura sobre", "pesquisar", "pesquisa", "procura"
    )
    private val questionTriggers = listOf(
        "o que e que e", "o que e", "o que sao", "quem e", "quem foi", "quem sao",
        "fala me de", "fala me sobre", "para que serve", "para que servem"
    )
    private val triggers = explicitTriggers + questionTriggers

    override fun score(n: String, t: List<String>): Int {
        if (explicitTriggers.any { n.startsWith(it) || n.contains(" $it ") }) return 80
        if (questionTriggers.any { n.startsWith(it) || n.contains(" $it ") }) return 60
        return 0
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        var q = n
        for (tr in triggers) {
            val i = q.indexOf(tr)
            if (i >= 0) {
                q = q.substring(i + tr.length)
                break
            }
        }
        q = q.replace(LEADING_ARTICLE, "").trim()
        if (q.isBlank()) return Answer("O que queres que pesquise?")

        val google = n.contains("google") || n.contains("internet")
        if (!google) {
            val summary = try { wikipedia(q) } catch (_: Exception) { null }
            if (summary != null) return Answer(summary)
        }
        return Answer("Vou procurar no Google.") { openGoogle(ctx, q) }
    }

    private fun wikipedia(q: String): String? {
        val search = JSONObject(
            Http.get(
                "https://pt.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" +
                    URLEncoder.encode(q, "UTF-8")
            )
        )
        val hits = search.getJSONObject("query").getJSONArray("search")
        for (i in 0 until hits.length()) {
            val title = hits.getJSONObject(i).getString("title")
            val summary = try {
                JSONObject(
                    Http.get(
                        "https://pt.wikipedia.org/api/rest_v1/page/summary/" +
                            URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20")
                    )
                )
            } catch (_: Exception) {
                continue
            }
            if (summary.optString("type").equals("disambiguation", ignoreCase = true)) continue
            val rawExtract = summary.optString("extract").trim()
            if (rawExtract.isBlank()) continue
            val cleaned = cleanForSpeech(rawExtract)
            if (cleaned.isBlank()) continue
            // as duas primeiras frases chegam para ouvir
            val sentences = cleaned.split(SENTENCE_SPLIT)
            val text = sentences.take(2).joinToString(" ").trim()
            return truncateAtWord(text, 400)
        }
        return null
    }

    private fun cleanForSpeech(s: String): String {
        var out = s
        repeat(2) {
            out = out.replace(PARENS, "").replace(BRACKETS, "")
        }
        return out
            .replace(SPACE_BEFORE_PUNCT, "$1")
            .replace(MULTI_SPACE, " ")
            .trim()
    }

    private fun truncateAtWord(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        val cut = text.lastIndexOf(' ', maxLen).let { if (it > 0) it else maxLen }
        return text.substring(0, cut).trimEnd(',', ';', ':', '-', ' ') + "…"
    }

    private fun openGoogle(ctx: Context, q: String) {
        val uri = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"))
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    companion object {
        private val LEADING_ARTICLE = Regex("^(o|a|os|as|um|uma) ")
        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
        private val PARENS = Regex("\\([^()]*\\)")
        private val BRACKETS = Regex("\\[[^\\[\\]]*\\]")
        private val SPACE_BEFORE_PUNCT = Regex("\\s+([.,;:!?])")
        private val MULTI_SPACE = Regex("\\s+")
    }
}
