package pl.kuba.przypominacz

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Synchronizacja z kalendarzami zapisanymi w telefonie (Kalendarz Google zsynchronizowany przez Androida).
 * Aplikacja sama niczego nie wysyła do internetu – czyta i zapisuje kalendarz telefonu,
 * a Android przesyła zmiany do Google tak jak przy zwykłym Kalendarzu.
 */
object CalendarImport {

    data class Cal(
        val id: Long,
        val name: String,
        val account: String,
        val isGoogle: Boolean,
        val writable: Boolean
    )

    data class Result(val added: Int, val updated: Int, val removed: Int)

    private const val PREFS = "import"
    private const val KEY_CALS = "calendars"
    private const val KEY_SKIP = "skipped"
    private const val KEY_TARGET = "target"
    private const val KEY_REMIND_MIGRATED = "remind_migrated_1"
    private const val KEY_REMIND = "google_remind"

    /** Przypomnienie dla wydarzeń z Google (minuty przed; -1 = brak). Domyślnie 1 godz. */
    fun googleRemind(ctx: Context): Int = prefs(ctx).getInt(KEY_REMIND, 60)

    fun setGoogleRemind(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_REMIND, v).apply()
    }

    private var remindCache = 60

    /** Wydarzenia całodniowe (np. święta) bez przypomnienia, z godziną – wg ustawienia. */
    private fun defaultRemind(minutes: Int): Int = if (minutes < 0) -1 else remindCache
    const val DAYS_AHEAD = 365L
    const val DEFAULT_DURATION_MIN = 60L

    // ---------- Uprawnienia i ustawienia ----------

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWrite(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selected(ctx: Context): Set<Long> =
        prefs(ctx).getStringSet(KEY_CALS, emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()

    fun setSelected(ctx: Context, ids: Set<Long>) {
        prefs(ctx).edit().putStringSet(KEY_CALS, ids.map { it.toString() }.toSet()).apply()
    }

    /** Kalendarz, do którego trafiają nowe wpisy z aplikacji (-1 = nie zapisuj). */
    fun target(ctx: Context): Long = prefs(ctx).getLong(KEY_TARGET, -1L)

    fun setTarget(ctx: Context, id: Long) {
        prefs(ctx).edit().putLong(KEY_TARGET, id).apply()
    }

    fun writesToGoogle(ctx: Context) = target(ctx) >= 0 && hasWrite(ctx)

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
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
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
                        isGoogle = (c.getString(3) ?: "") == "com.google",
                        writable = c.getInt(4) >= 500      // 500 = może dodawać wydarzenia
                    )
                )
            }
        }
        return out
    }

    // ---------- Pomocnicze ----------

    private class ExtId(val calId: Long, val eventId: Long, val begin: Long)

    private fun parseExt(ext: String): ExtId? {
        val p = ext.split(":")
        if (p.size != 4 || p[0] != "g") return null
        val cal = p[1].toLongOrNull() ?: return null
        val ev = p[2].toLongOrNull() ?: return null
        val b = p[3].toLongOrNull() ?: return null
        return ExtId(cal, ev, b)
    }

    /** Początek wydarzenia tak, jak zapisuje go kalendarz (całodniowe w UTC). */
    private fun startMillis(t: Task): Long =
        if (t.minutes < 0) t.day * 86_400_000L
        else LocalDate.ofEpochDay(t.day).atTime(t.minutes / 60, t.minutes % 60)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun fillTimes(cv: ContentValues, t: Task, durationMs: Long) {
        val start = startMillis(t)
        if (t.minutes < 0) {
            cv.put(CalendarContract.Events.ALL_DAY, 1)
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            cv.put(CalendarContract.Events.DTSTART, start)
            cv.put(CalendarContract.Events.DTEND, start + 86_400_000L)
        } else {
            cv.put(CalendarContract.Events.ALL_DAY, 0)
            cv.put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            cv.put(CalendarContract.Events.DTSTART, start)
            cv.put(CalendarContract.Events.DTEND, start + durationMs)
        }
    }

    private fun description(t: Task): String = t.notes

    // ---------- Zapis do Kalendarza Google ----------

    /** Nowy wpis z aplikacji → nowe wydarzenie w Kalendarzu Google. Ustawia t.ext. */
    fun pushNew(ctx: Context, t: Task): Boolean {
        val cal = target(ctx)
        if (cal < 0 || !hasWrite(ctx)) return false
        return try {
            val cv = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, cal)
                put(CalendarContract.Events.TITLE, t.title)
                put(CalendarContract.Events.EVENT_LOCATION, t.place)
                put(CalendarContract.Events.DESCRIPTION, description(t))
            }
            fillTimes(cv, t, DEFAULT_DURATION_MIN * 60_000L)
            val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv) ?: return false
            val eventId = ContentUris.parseId(uri)
            t.ext = "g:$cal:$eventId:${startMillis(t)}"
            true
        } catch (e: Exception) {
            false
        }
    }

    private class EventInfo(val recurring: Boolean, val durationMs: Long, val calId: Long)

    private fun eventInfo(ctx: Context, eventId: Long): EventInfo? {
        val proj = arrayOf(
            CalendarContract.Events.RRULE,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.DELETED
        )
        ctx.contentResolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            proj, null, null, null
        )?.use { c ->
            if (!c.moveToFirst()) return null
            if (!c.isNull(5) && c.getInt(5) == 1) return null
            val recurring = !c.getString(0).isNullOrBlank() || !c.isNull(1)
            val dur = if (!c.isNull(2) && !c.isNull(3)) c.getLong(3) - c.getLong(2) else 0L
            return EventInfo(recurring, if (dur > 0) dur else DEFAULT_DURATION_MIN * 60_000L, c.getLong(4))
        }
        return null
    }

    /** Czy zmiany tego wpisu da się odesłać do Google (nie cykliczne, kalendarz z prawem zapisu). */
    fun canWriteBack(ctx: Context, t: Task): Boolean {
        if (!t.fromGoogle || !hasWrite(ctx)) return false
        val id = parseExt(t.ext) ?: return false
        val info = (try { eventInfo(ctx, id.eventId) } catch (e: Exception) { null }) ?: return false
        if (info.recurring) return false
        return calendars(ctx).any { it.id == id.calId && it.writable }
    }

    /** Zmieniony wpis → aktualizacja wydarzenia w Google. Ustawia nowy t.ext. */
    fun pushUpdate(ctx: Context, t: Task): Boolean {
        if (!canWriteBack(ctx, t)) return false
        val id = parseExt(t.ext) ?: return false
        return try {
            val info = eventInfo(ctx, id.eventId) ?: return false
            val cv = ContentValues().apply {
                put(CalendarContract.Events.TITLE, t.title)
                put(CalendarContract.Events.EVENT_LOCATION, t.place)
                put(CalendarContract.Events.DESCRIPTION, description(t))
            }
            fillTimes(cv, t, info.durationMs)
            ctx.contentResolver.update(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.eventId),
                cv, null, null
            )
            t.ext = "g:${id.calId}:${id.eventId}:${startMillis(t)}"
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Usuwa wydarzenie z Kalendarza Google. */
    fun deleteRemote(ctx: Context, t: Task): Boolean {
        if (!canWriteBack(ctx, t)) return false
        val id = parseExt(t.ext) ?: return false
        return try {
            ctx.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.eventId), null, null
            ) > 0
        } catch (e: Exception) {
            false
        }
    }

    // ---------- Pobieranie z Kalendarza Google ----------

    private fun guessType(title: String, dict: List<DictItem>): Int {
        val t = title.lowercase(Fmt.PL)
        // najpierw słowniki – tytuł z Google zgodny z pozycją słownika dostaje jej rodzaj
        dict.firstOrNull { it.text.lowercase(Fmt.PL) == t }?.let { return it.cat }
        dict.filter { it.text.length >= 4 }
            .sortedByDescending { it.text.length }
            .firstOrNull { t.contains(it.text.lowercase(Fmt.PL)) }
            ?.let { return it.cat }
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
        remindCache = googleRemind(ctx)
        val db = Db.get(ctx)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val begin = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(DAYS_AHEAD).atStartOfDay(zone).toInstant().toEpochMilli()
        val skipped = prefs(ctx).getStringSet(KEY_SKIP, emptySet())!!
        val dict = db.dictAll()
        val migrated = prefs(ctx).getBoolean(KEY_REMIND_MIGRATED, false)
        val catIds = Cats.all().map { it.id }.toSet()

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

                var old = db.byExt(ext)
                // jednorazowo: starsze pobrane wpisy (bez przypomnienia) dostają przypomnienie
                if (old != null && !migrated && old.remind < 0 && old.minutes >= 0 && !old.done) {
                    old.remind = defaultRemind(old.minutes)
                    db.save(old)
                    Reminders.schedule(ctx, old)
                    old = db.byExt(ext)
                }
                if (old == null) {
                    val t = Task(
                        title = title, type = guessType(title, dict).let { if (catIds.isEmpty() || it in catIds) it else Task.TYPE_EVENT }, day = day, minutes = minutes,
                        place = place, notes = notes, remind = defaultRemind(minutes), done = false, ext = ext
                    )
                    db.save(t)
                    Reminders.schedule(ctx, t)
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
            val calId = parseExt(t.ext)?.calId
            if (calId != null && calId in calIds && t.ext !in found) {
                Reminders.cancel(ctx, t.id)
                db.delete(t.id)
                removed++
            }
        }

        prefs(ctx).edit().putBoolean(KEY_REMIND_MIGRATED, true).apply()
        TasksWidget.refresh(ctx)
        return Result(added, updated, removed)
    }

    /** Wysyła do Google wcześniejsze wpisy z aplikacji (od dziś), które jeszcze tam nie trafiły. */
    fun pushExisting(ctx: Context): Int {
        if (!writesToGoogle(ctx)) return 0
        val db = Db.get(ctx)
        var n = 0
        db.localFuture(Fmt.today()).forEach { t ->
            if (pushNew(ctx, t)) {
                db.save(t)
                n++
            }
        }
        TasksWidget.refresh(ctx)
        return n
    }
}
