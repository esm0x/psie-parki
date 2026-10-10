package com.adminstack.psieparki.data

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The part of OSM's opening_hours syntax that vets in Poland actually use:
 *
 *     24/7
 *     Mo-Fr 08:00-20:00; Sa 09:00-14:00
 *     Mo,We,Fr 10:00-13:00,15:00-19:00; Su off
 *     Mo-Su 20:00-02:00
 *
 * Anything beyond that (months, weeks, holidays, "sunrise", comments) makes [parse]
 * return null, and the app shows the raw text instead of guessing "open". A wrong
 * "otwarte" is worse than none: someone could drive to a closed clinic at night.
 */
class OpeningHours private constructor(
    /** Minutes from Monday 00:00, as [start, end) spans; an overnight span runs past 7 days. */
    private val spans: List<IntRange>,
    val alwaysOpen: Boolean,
) {
    sealed interface Status {
        data class Open(val until: LocalTime?) : Status
        data class Closed(val opens: LocalDateTime?) : Status
    }

    fun status(now: LocalDateTime): Status {
        if (alwaysOpen) return Status.Open(null)
        val m = minuteOfWeek(now)
        val open = spans.firstOrNull { m in it || (m + WEEK) in it }
        if (open != null) {
            val endMinute = open.last + 1
            return Status.Open(LocalTime.of((endMinute % DAY) / 60, endMinute % 60))
        }
        val next = spans.flatMap { listOf(it.first, it.first + WEEK) }.filter { it > m }.minOrNull()
        return Status.Closed(next?.let { now.withSecond(0).withNano(0).plusMinutes((it - m).toLong()) })
    }

    companion object {
        private const val DAY = 24 * 60
        private const val WEEK = 7 * DAY
        private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
        private val TIME_SPAN = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$""")

        private fun minuteOfWeek(t: LocalDateTime) =
            (t.dayOfWeek.value - 1) * DAY + t.hour * 60 + t.minute

        fun parse(raw: String?): OpeningHours? {
            val text = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (text == "24/7" || text == "mo-su 00:00-24:00") return OpeningHours(emptyList(), alwaysOpen = true)

            // OSM semantics: a later rule replaces earlier ones for the days it names.
            val perDay = arrayOfNulls<List<Pair<Int, Int>>>(7)
            for (rule in text.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
                val parts = rule.split(Regex("\\s+"), limit = 2)
                val (dayPart, timePart) = when {
                    parts[0].first().isLetter() && parts.size == 2 -> parts[0] to parts[1]
                    parts[0].first().isLetter() -> parts[0] to null
                    else -> null to rule
                }
                if (dayPart == "ph" || dayPart?.startsWith("ph") == true) continue  // holidays: unknown here
                // An unknown day word ("Xx", "Jan") means a rule this parser does not
                // understand, never "every day".
                val days = if (dayPart == null) (0..6).toList() else days(dayPart) ?: return null
                if (days.isEmpty()) return null
                val times = when (timePart?.trim()) {
                    null -> return null
                    "off", "closed" -> emptyList()
                    else -> timePart.split(",").map { span ->
                        val m = TIME_SPAN.matchEntire(span.trim()) ?: return null
                        val (h1, m1, h2, m2) = m.destructured.toList().map { it.toInt() }
                        if (h1 > 24 || h2 > 24 || m1 > 59 || m2 > 59) return null
                        h1 * 60 + m1 to h2 * 60 + m2
                    }
                }
                days.forEach { perDay[it] = times }
            }
            val spans = perDay.flatMapIndexed { day, times ->
                times.orEmpty().map { (from, to) ->
                    val start = day * DAY + from
                    val end = day * DAY + if (to <= from) to + DAY else to  // 20:00-02:00 runs overnight
                    start until end
                }
            }
            if (spans.isEmpty()) return null  // "off" everywhere is not something to trust
            return OpeningHours(spans, alwaysOpen = false)
        }

        /** "mo-fr", "mo,we,fr", "sa", "mo-we,fr" → day indexes; null when not a day list. */
        private fun days(spec: String): List<Int>? = spec.split(",").flatMap { part ->
            val range = part.split("-")
            val ids = range.map { DAYS.indexOf(it) }
            when {
                ids.any { it < 0 } -> return null
                ids.size == 1 -> listOf(ids[0])
                ids.size == 2 && ids[0] <= ids[1] -> (ids[0]..ids[1]).toList()
                ids.size == 2 -> (ids[0]..6).toList() + (0..ids[1]).toList()      // "sa-mo"
                else -> return null
            }
        }
    }
}

/** "Otwarte do 18:00", "Otwarte całą dobę", "Zamknięte · otworzy jutro o 8:00". */
fun describe(status: OpeningHours.Status, now: LocalDateTime): String = when (status) {
    is OpeningHours.Status.Open -> status.until?.let { "Otwarte do ${it.hour}:%02d".format(it.minute) } ?: "Otwarte całą dobę"
    is OpeningHours.Status.Closed -> {
        val at = status.opens
        when {
            at == null -> "Zamknięte"
            at.toLocalDate() == now.toLocalDate() -> "Zamknięte · otworzy o ${at.hour}:%02d".format(at.minute)
            at.toLocalDate() == now.toLocalDate().plusDays(1) -> "Zamknięte · otworzy jutro o ${at.hour}:%02d".format(at.minute)
            else -> "Zamknięte · otworzy ${dayName(at.dayOfWeek)} o ${at.hour}:%02d".format(at.minute)
        }
    }
}

private fun dayName(d: DayOfWeek) = when (d) {
    DayOfWeek.MONDAY -> "w poniedziałek"
    DayOfWeek.TUESDAY -> "we wtorek"
    DayOfWeek.WEDNESDAY -> "w środę"
    DayOfWeek.THURSDAY -> "w czwartek"
    DayOfWeek.FRIDAY -> "w piątek"
    DayOfWeek.SATURDAY -> "w sobotę"
    DayOfWeek.SUNDAY -> "w niedzielę"
}
