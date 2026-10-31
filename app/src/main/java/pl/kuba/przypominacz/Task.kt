package pl.kuba.przypominacz

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Task(
    var id: Long = 0,
    var title: String = "",
    var type: Int = TYPE_TASK,
    var day: Long = 0,          // LocalDate.toEpochDay()
    var minutes: Int = -1,      // minuty od północy, -1 = bez godziny
    var place: String = "",
    var notes: String = "",
    var remind: Int = -1,       // ile minut przed, -1 = brak przypomnienia
    var done: Boolean = false
) {
    companion object {
        const val TYPE_TASK = 0
        const val TYPE_DOCTOR = 1
        const val TYPE_EVENT = 2
    }
}

object Fmt {
    val PL: Locale = Locale.forLanguageTag("pl-PL")

    const val NEAR_MINUTES = 60          // co uznajemy za "blisko" innego wpisu

    const val RED = 0xFFD32F2F.toInt()
    const val ORANGE = 0xFFEF6C00.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val GRAY = 0xFF757575.toInt()

    fun today(): Long = LocalDate.now().toEpochDay()

    fun time(m: Int): String =
        if (m < 0) "cały dzień" else String.format(Locale.US, "%02d:%02d", m / 60, m % 60)

    fun typeName(t: Int) = when (t) {
        Task.TYPE_DOCTOR -> "Wizyta u lekarza"
        Task.TYPE_EVENT -> "Wydarzenie"
        else -> "Zadanie"
    }

    fun typeEmoji(t: Int) = when (t) {
        Task.TYPE_DOCTOR -> "🩺"
        Task.TYPE_EVENT -> "📅"
        else -> "✅"
    }

    fun typeColor(t: Int) = when (t) {
        Task.TYPE_DOCTOR -> 0xFF00897B.toInt()
        Task.TYPE_EVENT -> 0xFF8E24AA.toInt()
        else -> 0xFF1E88E5.toInt()
    }

    private fun cap(s: String) = s.replaceFirstChar { it.titlecase(PL) }

    fun shortDate(day: Long): String {
        val d = LocalDate.ofEpochDay(day)
        val pattern = if (d.year == LocalDate.now().year) "EEE, d MMM" else "EEE, d MMM yyyy"
        return cap(d.format(DateTimeFormatter.ofPattern(pattern, PL)))
    }

    fun dayLabel(day: Long): String = when (day - today()) {
        0L -> "Dziś · " + shortDate(day)
        1L -> "Jutro · " + shortDate(day)
        -1L -> "Wczoraj · " + shortDate(day)
        else -> shortDate(day)
    }

    fun fullDate(day: Long): String =
        cap(LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", PL)))

    fun headerDate(): String =
        cap(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", PL)))

    val REMIND_LABELS = arrayOf(
        "Bez przypomnienia",
        "W chwili terminu",
        "10 min przed",
        "30 min przed",
        "1 godz. przed",
        "2 godz. przed",
        "Dzień wcześniej",
        "2 dni wcześniej"
    )
    val REMIND_VALUES = intArrayOf(-1, 0, 10, 30, 60, 120, 1440, 2880)

    fun remindIndex(v: Int): Int = REMIND_VALUES.indexOf(v).let { if (it < 0) 0 else it }
}
