package pt.sangriaman.relogiovoz

import android.content.Context
import android.util.Log
import pt.sangriaman.relogiovoz.skills.AlarmSkill
import pt.sangriaman.relogiovoz.skills.Answer
import pt.sangriaman.relogiovoz.skills.ClockSkill
import pt.sangriaman.relogiovoz.skills.HomeSkill
import pt.sangriaman.relogiovoz.skills.MediaSkill
import pt.sangriaman.relogiovoz.skills.MusicSkill
import pt.sangriaman.relogiovoz.skills.SearchSkill
import pt.sangriaman.relogiovoz.skills.TimerSkill
import pt.sangriaman.relogiovoz.skills.WeatherSkill

/** Decide que habilidade responde a cada frase escolhendo a maior pontuação. */
class Brain {
    companion object {
        private const val TAG = "Brain"
    }

    private val skills = listOf(
        MediaSkill(),
        ClockSkill(),
        TimerSkill(),
        AlarmSkill(),
        WeatherSkill(),
        MusicSkill(),
        HomeSkill(),
        SearchSkill()
    )

    suspend fun answer(ctx: Context, raw: String): Answer {
        val n = Txt.norm(raw)
        val t = Txt.tokens(raw)
        if (t.isEmpty()) return Answer("Não ouvi nada.")
        val skill = skills
            .map { it to it.score(n, t).coerceIn(0, 100) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
            ?: return Answer("Não sei fazer isso. Posso dizer as horas, o tempo, pesquisar ou tocar música.")
        return try {
            skill.handle(ctx, n, t, raw)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Erro de rede ao responder a '$raw'", e)
            Answer("Não consegui ligar à internet.")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao responder a '$raw'", e)
            Answer("Desculpa, aconteceu um problema. Tenta outra vez.")
        }
    }
}
