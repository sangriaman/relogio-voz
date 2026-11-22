package pt.sangriaman.relogiovoz.skills

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import pt.sangriaman.relogiovoz.PtNumbers
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val PT = Locale("pt", "PT")

/** "Que horas são?" / "Que dia é hoje?" */
class ClockSkill : Skill {
    override fun matches(n: String, t: List<String>) =
        n.contains("que horas") || n == "horas" || n.contains("horas sao") ||
            n.contains("que dia") || n.contains("dia de hoje") || n.contains("data de hoje")

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        if (n.contains("dia") || n.contains("data")) {
            val d = LocalDate.now()
            val dow = d.dayOfWeek.getDisplayName(TextStyle.FULL, PT)
            val month = d.month.getDisplayName(TextStyle.FULL, PT)
            return Answer("Hoje é $dow, ${d.dayOfMonth} de $month.")
        }
        val now = LocalTime.now()
        val h = now.hour
        val m = now.minute
        val hours = when (h) {
            0 -> "É meia-noite"
            1 -> "É uma hora"
            12 -> "É meio-dia"
            13 -> "É uma hora"
            else -> "São ${if (h > 12) h - 12 else h} horas"
        }
        val mins = when (m) {
            0 -> ""
            1 -> " e um minuto"
            30 -> " e meia"
            else -> " e $m"
        }
        val period = when (h) {
            in 1..11 -> " da manhã"
            in 13..19 -> " da tarde"
            in 20..23 -> " da noite"
            else -> ""
        }
        return Answer("$hours$mins$period.")
    }
}

/** "Temporizador de 10 minutos" — usa a app Relógio do sistema. */
class TimerSkill : Skill {
    override fun matches(n: String, t: List<String>) =
        n.contains("temporizador") || n.contains("cronometro") || n.contains("contagem")

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        var seconds = 0
        var i = 0
        while (i < t.size) {
            if (t[i] == "meia" && i + 1 < t.size && t[i + 1].startsWith("hora")) {
                seconds += 1800; i += 2; continue
            }
            val num = PtNumbers.parseAt(t, i)
            if (num != null && num.second < t.size) {
                val unit = t[num.second]
                val mult = when {
                    unit.startsWith("segundo") -> 1
                    unit.startsWith("minuto") -> 60
                    unit.startsWith("hora") -> 3600
                    else -> 0
                }
                if (mult > 0) {
                    seconds += num.first * mult
                    // "um minuto e meio"
                    if (num.second + 2 < t.size && t[num.second + 1] == "e" && t[num.second + 2] == "meio") {
                        seconds += mult / 2
                    }
                    i = num.second + 1
                    continue
                }
            }
            i++
        }
        if (seconds <= 0) return Answer("Não percebi a duração. Diz, por exemplo, temporizador de dez minutos.")

        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Manel")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Answer("Temporizador de ${describe(seconds)} a contar.")
        } catch (e: Exception) {
            Answer("Não consegui criar o temporizador. Confirma que o Manel pode abrir outras apps.")
        }
    }

    private fun describe(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += if (h == 1) "uma hora" else "$h horas"
        if (m > 0) parts += if (m == 1) "um minuto" else "$m minutos"
        if (sec > 0) parts += if (sec == 1) "um segundo" else "$sec segundos"
        return parts.joinToString(" e ")
    }
}

/** "Acorda-me às sete e meia" / "Alarme para as oito" */
class AlarmSkill : Skill {
    override fun matches(n: String, t: List<String>) =
        n.contains("alarme") || n.contains("despertador") || n.contains("acorda") || n.contains("acordar")

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        // a hora vem normalmente depois de "as"
        val start = t.indexOfFirst { it == "as" || it == "a" }.let { if (it < 0) 0 else it + 1 }
        val hourNum = PtNumbers.first(t, start)
            ?: return Answer("A que horas? Diz, por exemplo, acorda-me às sete e meia.")
        var hour = hourNum.first
        var minute = 0
        var j = hourNum.third
        if (j < t.size && t[j].startsWith("hora")) j++
        if (j < t.size && t[j] == "e" && j + 1 < t.size) {
            when {
                t[j + 1] == "meia" -> minute = 30
                t[j + 1] == "um" && j + 2 < t.size && t[j + 2] == "quarto" -> minute = 15
                t[j + 1] == "quinze" -> minute = 15
                else -> PtNumbers.parseAt(t, j + 1)?.let { minute = it.first }
            }
        } else if (j < t.size) {
            // "sete trinta"
            PtNumbers.parseAt(t, j)?.let { if (it.first < 60) minute = it.first }
        }
        if ((n.contains("da tarde") || n.contains("da noite")) && hour < 12) hour += 12
        if (hour > 23 || minute > 59) return Answer("Essa hora não é válida.")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Manel")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            Answer("Alarme marcado para as $hour${if (minute > 0) " e $minute" else ""}.")
        } catch (e: Exception) {
            Answer("Não consegui marcar o alarme. Confirma que o Manel pode abrir outras apps.")
        }
    }
}
