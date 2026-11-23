package pt.sangriaman.relogiovoz

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay

/**
 * Rotina da Sofia: "bom dia" nos dias úteis à hora de acordar, "boa noite" à
 * hora de deitar, e o modo noite (ecrã escuro só com as horas) entre as duas.
 */
object Routine {

    /** Ao fim de semana e nos feriados não há despertador; o modo noite acaba a esta hora. */
    private val RELAXED_MORNING: LocalTime = LocalTime.of(9, 30)

    private fun time(s: String, fallback: LocalTime): LocalTime = try {
        val (h, m) = s.split(":").map { it.trim().toInt() }
        LocalTime.of(h, m)
    } catch (_: Exception) { fallback }

    fun bed(c: Context) = time(Prefs.bedTime(c), LocalTime.of(21, 30))
    fun wake(c: Context) = time(Prefs.wakeTime(c), LocalTime.of(7, 30))

    /** Há escola/despertador neste dia? */
    fun isSchoolDay(d: LocalDate) =
        d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY &&
            !Holidays.isHoliday(d) && !SchoolBreaks.isBreak(d)

    /** Hora a que a manhã começa neste dia (despertador ou manhã livre). */
    fun morning(c: Context, d: LocalDate): LocalTime = if (isSchoolDay(d)) wake(c) else RELAXED_MORNING

    /** Está na hora de o relógio estar em modo noite? */
    fun isNight(c: Context, now: LocalDateTime = LocalDateTime.now()): Boolean {
        val t = now.toLocalTime()
        val bed = bed(c)
        val morning = morning(c, now.toLocalDate())
        return if (bed > morning) t >= bed || t < morning else t in bed..morning
    }

    /** O que dizer agora (uma vez por dia em cada momento); null se nada. */
    fun due(c: Context, now: LocalDateTime = LocalDateTime.now()): Pair<String, String>? {
        val d = now.toLocalDate()
        val t = now.toLocalTime().withSecond(0).withNano(0)
        val name = Prefs.childName(c)
        val hi = if (name.isBlank()) "" else ", $name"
        if (isSchoolDay(d) && t == wake(c)) {
            return "acordar-$d" to "Bom dia$hi! Está na hora de acordar."
        }
        if (t == bed(c)) {
            return "deitar-$d" to "Boa noite$hi! Está na hora de dormir."
        }
        return null
    }
}

/** Feriados nacionais de Portugal, Carnaval e o feriado municipal de Guimarães (24 de junho). */
object Holidays {
    private val FIXED = listOf(
        MonthDay.of(1, 1), MonthDay.of(4, 25), MonthDay.of(5, 1), MonthDay.of(6, 10),
        MonthDay.of(6, 24), // Guimarães (S. João)
        MonthDay.of(8, 15), MonthDay.of(10, 5), MonthDay.of(11, 1), MonthDay.of(12, 1),
        MonthDay.of(12, 8), MonthDay.of(12, 25)
    )

    /** Domingo de Páscoa (algoritmo de Meeus/Jones/Butcher). */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    fun isHoliday(d: LocalDate): Boolean {
        if (MonthDay.from(d) in FIXED) return true
        val e = easter(d.year)
        return d == e.minusDays(47) || // Carnaval
            d == e.minusDays(2) ||     // Sexta-feira Santa
            d == e ||                  // Páscoa
            d == e.plusDays(60)        // Corpo de Deus
    }
}

/**
 * Férias escolares (calendário nacional, Despacho n.º 8368/2024), para a Sofia (6.º ano).
 * Datas inclusivas. Atualizar quando sair o calendário dos anos seguintes.
 */
object SchoolBreaks {
    private val BREAKS = listOf(
        // 2026/2027
        LocalDate.of(2026, 12, 16) to LocalDate.of(2026, 12, 31), // Natal
        LocalDate.of(2027, 2, 8) to LocalDate.of(2027, 2, 10),    // Carnaval
        LocalDate.of(2027, 3, 22) to LocalDate.of(2027, 4, 2),    // Páscoa
        LocalDate.of(2027, 6, 12) to LocalDate.of(2027, 9, 12),   // Verão (fim das aulas do 6.º ano a 11/6; regresso aprox.)
        // 2027/2028
        LocalDate.of(2027, 12, 20) to LocalDate.of(2027, 12, 31), // Natal
        LocalDate.of(2028, 2, 28) to LocalDate.of(2028, 3, 1),    // Carnaval
        LocalDate.of(2028, 4, 3) to LocalDate.of(2028, 4, 17)     // Páscoa
    )

    fun isBreak(d: LocalDate) = BREAKS.any { (a, b) -> !d.isBefore(a) && !d.isAfter(b) }
}
