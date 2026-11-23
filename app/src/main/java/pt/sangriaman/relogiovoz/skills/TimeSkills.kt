package pt.sangriaman.relogiovoz.skills

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import pt.sangriaman.relogiovoz.PtNumbers
import pt.sangriaman.relogiovoz.Words
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

private val PT = Locale("pt", "PT")

/** "Que horas são?" / "Que dia é hoje?" */
class ClockSkill : Skill {
    override fun score(n: String, t: List<String>): Int {
        val match = n.contains("que horas") || n == "horas" || n.contains("horas sao") ||
            n.contains("que dia") || n.contains("dia de hoje") || n.contains("data de hoje") ||
            n.contains("qual e a data")
        return if (match) 90 else 0
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        if (n.contains("dia") || n.contains("data")) {
            val d = LocalDate.now()
            val dow = d.dayOfWeek.getDisplayName(TextStyle.FULL, PT)
            val month = d.month.getDisplayName(TextStyle.FULL, PT)
            return Answer("Hoje é $dow, ${Words.of(d.dayOfMonth)} de $month.")
        }
        val now = LocalTime.now()
        val h = now.hour
        val m = now.minute
        val hours = when (h) {
            0 -> "É meia-noite"
            1 -> "É uma hora"
            12 -> "É meio-dia"
            13 -> "É uma hora"
            else -> "São ${Words.of(if (h > 12) h - 12 else h, true)} horas"
        }
        val mins = when (m) {
            0 -> ""
            1 -> " e um minuto"
            30 -> " e meia"
            else -> " e ${Words.of(m)}"
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
    override fun score(n: String, t: List<String>): Int {
        val match = n.contains("temporizador") || n.contains("cronometro") ||
            n.contains("contagem") || t.hasAny("timer")
        return if (match) 90 else 0
    }

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
                    // "um minuto e meio" / "uma hora e meia"
                    if (num.second + 2 < t.size && t[num.second + 1] == "e" &&
                        (t[num.second + 2] == "meio" || t[num.second + 2] == "meia")
                    ) {
                        seconds += mult / 2
                        i = num.second + 3
                        continue
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
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Tricky")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val appCtx = ctx.applicationContext
        return Answer("Temporizador de ${describe(seconds)} a contar.") {
            try {
                appCtx.startActivity(intent)
            } catch (e: Exception) {
                Log.e("TimerSkill", "Erro ao iniciar temporizador", e)
            }
        }
    }

    private fun describe(s: Int): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val parts = mutableListOf<String>()
        if (h > 0) parts += if (h == 1) "uma hora" else "${Words.of(h, true)} horas"
        if (m > 0) parts += if (m == 1) "um minuto" else "${Words.of(m)} minutos"
        if (sec > 0) parts += if (sec == 1) "um segundo" else "${Words.of(sec)} segundos"
        return parts.joinToString(" e ")
    }
}

/** "Acorda-me às sete e meia" / "Alarme para as oito" */
class AlarmSkill : Skill {
    override fun score(n: String, t: List<String>): Int {
        val match = n.contains("alarme") || n.contains("despertador") ||
            n.contains("acorda") || n.contains("acordar")
        return if (match) 90 else 0
    }

    override suspend fun handle(ctx: Context, n: String, t: List<String>, raw: String): Answer {
        var hour: Int
        var minute = 0

        if (n.contains("meio dia")) {
            hour = 12
            val idx = t.indexOf("dia")
            if (idx >= 0) minute = parseMinutes(t, idx + 1)
        } else if (n.contains("meia noite")) {
            hour = 0
            val idx = t.indexOf("noite")
            if (idx >= 0) minute = parseMinutes(t, idx + 1)
        } else {
            // a hora vem normalmente depois de "as"
            val start = t.indexOfFirst { it == "as" || it == "a" }.let { if (it < 0) 0 else it + 1 }
            val hourNum = PtNumbers.first(t, start)
                ?: return Answer("A que horas? Diz, por exemplo, acorda-me às sete e meia.")
            hour = hourNum.first
            var j = hourNum.third
            if (j < t.size && t[j].startsWith("hora")) j++
            minute = parseMinutes(t, j)
            if ((n.contains("da tarde") || n.contains("da noite")) && hour < 12) hour += 12
            if (n.contains("da manha") && hour == 12) hour = 0
        }

        if (hour !in 0..23 || minute !in 0..59) return Answer("Essa hora não é válida.")

        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Tricky")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val appCtx = ctx.applicationContext
        return Answer("Alarme marcado para ${formatAlarmTime(hour, minute)}.") {
            try {
                appCtx.startActivity(intent)
            } catch (e: Exception) {
                Log.e("AlarmSkill", "Erro ao marcar alarme", e)
            }
        }
    }

    private fun parseMinutes(t: List<String>, start: Int): Int {
        if (start >= t.size) return 0
        if (t[start] == "e" && start + 1 < t.size) {
            return when {
                t[start + 1] == "meia" -> 30
                t[start + 1] == "um" && start + 2 < t.size && t[start + 2] == "quarto" -> 15
                t[start + 1] == "quinze" -> 15
                else -> PtNumbers.parseAt(t, start + 1)?.first ?: 0
            }
        }
        // "sete trinta"
        return PtNumbers.parseAt(t, start)?.takeIf { it.first < 60 }?.first ?: 0
    }

    private fun formatAlarmTime(h: Int, m: Int): String {
        val hours = when (h) {
            0 -> "a meia-noite"
            1 -> "a uma hora"
            12 -> "o meio-dia"
            13 -> "a uma hora"
            else -> "as ${Words.of(if (h > 12) h - 12 else h, true)} horas"
        }
        val mins = when (m) {
            0 -> ""
            1 -> " e um minuto"
            30 -> " e meia"
            else -> " e ${Words.of(m)}"
        }
        val period = when (h) {
            in 1..11 -> " da manhã"
            in 13..19 -> " da tarde"
            in 20..23 -> " da noite"
            else -> ""
        }
        return "$hours$mins$period"
    }
}
