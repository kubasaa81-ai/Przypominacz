package pl.kuba.przypominacz

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pobieranie wydarzeń z kalendarzy zapisanych w telefonie (Kalendarz Google zsynchronizowany
 * przez Androida). Aplikacja niczego nie wysyła do internetu – tylko czyta to, co już jest w telefonie.
 */
object CalendarImport {

    data class Cal(val id: Long, val name: String, val account: String, val isGoogle: Boolean)
    data class Result(val added: Int, val updated: Int, val removed: Int)

    private const val PREFS = "import"
    private const val KEY_CALS = "calendars"
    private const val KEY_SKIP = "skipped"
    const val DAYS_AHEAD = 365L

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selected(ctx: Context): Set<Long> =
        prefs(ctx).getStringSet(KEY_CALS, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun setSelected(ctx: Context, ids: Set<Long>) {
        prefs(ctx).edit().putStringSet(KEY_CALS, ids.map { it.toString() }.toSet()).apply()
    }

    /** Usunięty w aplikacji wpis z Google nie wraca przy kolejnym pobraniu. */
    fun skip(ctx: Context, ext: String) {
        val s = HashSet(prefs(ctx).getStringSet(KEY_SKIP, emptySet())!!)
        s.add(ext)
        prefs(ctx).edit().putStringSet(KEY_SKIP, s).apply()
    }

    fun calendars(ctx: Context): List<Cal> {
        if (!hasPermission(ctx)) return emptyList()
        val out = ArrayList<Cal>()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.VISIBLE}=1", null,
            CalendarContract.Calendars.ACCOUNT_NAME + " ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Cal(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "Kalendarz",
                        account = c.getString(2) ?: "",
                        isGoogle = (c.getString(3) ?: "") == "com.google"
                    )
                )
            }
        }
        return out
    }

    private fun guessType(title: String): Int {
        val t = title.lowercase(Fmt.PL)
        val doctor = listOf(
            "lekarz", "dr ", "dr.", "doktor", "dentyst", "stomatolog", "przychodni", "wizyt",
            "badani", "pediatr", "okulist", "ortoped", "kardiolog", "laryngolog", "dermatolog",
            "szczepien", "szczepień", "rehabilit", "usg", "rtg", "fizjo"
        )
        return if (doctor.any { t.contains(it) }) Task.TYPE_DOCTOR else Task.TYPE_EVENT
    }

    /** Pobiera wydarzenia od dziś na rok do przodu z wybranych kalendarzy. */
    fun run(ctx: Context, calIds: Set<Long> = selected(ctx)): Result {
        if (!hasPermission(ctx) || calIds.isEmpty()) return Result(0, 0, 0)
        val db = Db.get(ctx)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val begin = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(DAYS_AHEAD).atStartOfDay(zone).toInstant().toEpochMilli()
        val skipped = prefs(ctx).getStringSet(KEY_SKIP, emptySet())!!

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.STATUS
        )
        val where = "${CalendarContract.Instances.CALENDAR_ID} IN (${calIds.joinToString(",")})"

        var added = 0
        var updated = 0
        val found = HashSet<String>()

        ctx.contentResolver.query(uri, proj, where, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
            while (c.moveToNext()) {
                if (!c.isNull(7) && c.getInt(7) == 2) continue   // 2 = odwołane
                val eventId = c.getLong(0)
                val start = c.getLong(1)
                val title = (c.getString(2) ?: "").trim().ifEmpty { "(bez tytułu)" }
                val place = (c.getString(3) ?: "").trim()
                val notes = (c.getString(4) ?: "").trim().take(500)
                val allDay = c.getInt(5) == 1
                val calId = c.getLong(6)

                val ext = "g:$calId:$eventId:$start"
                found.add(ext)
                if (ext in skipped) continue

                val day: Long
                val minutes: Int
                if (allDay) {
                    day = Math.floorDiv(start, 86_400_000L)      // dni całodniowe są w UTC
                    minutes = -1
                } else {
                    val z = Instant.ofEpochMilli(start).atZone(zone)
                    day = z.toLocalDate().toEpochDay()
                    minutes = z.hour * 60 + z.minute
                }

                val old = db.byExt(ext)
                if (old == null) {
                    val t = Task(
                        title = title, type = guessType(title), day = day, minutes = minutes,
                        place = place, notes = notes, remind = -1, done = false, ext = ext
                    )
                    db.save(t)
                    added++
                } else if (old.title != title || old.place != place || old.notes != notes ||
                    old.day != day || old.minutes != minutes
                ) {
                    old.title = title
                    old.place = place
                    old.notes = notes
                    old.day = day
                    old.minutes = minutes
                    db.save(old)
                    Reminders.schedule(ctx, old)
                    updated++
                }
            }
        }

        // Wydarzenia usunięte lub przesunięte w Kalendarzu Google znikają też tutaj
        var removed = 0
        db.importedFrom(today.toEpochDay()).forEach { t ->
            val calId = t.ext.split(":").getOrNull(1)?.toLongOrNull()
            if (calId != null && calId in calIds && t.ext !in found) {
                Reminders.cancel(ctx, t.id)
                db.delete(t.id)
                removed++
            }
        }

        TasksWidget.refresh(ctx)
        return Result(added, updated, removed)
    }
}
