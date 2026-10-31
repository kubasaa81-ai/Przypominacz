package pl.kuba.przypominacz

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Lokalna baza SQLite – wszystko zostaje w telefonie, bez chmury. */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "przypominacz.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx).also { inst = it }
        }
        private const val ORDER = "day ASC, minutes ASC, id ASC"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE tasks(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "title TEXT NOT NULL," +
                "type INTEGER NOT NULL," +
                "day INTEGER NOT NULL," +
                "minutes INTEGER NOT NULL," +
                "place TEXT," +
                "notes TEXT," +
                "remind INTEGER NOT NULL," +
                "done INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX idx_day ON tasks(day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun values(t: Task) = ContentValues().apply {
        put("title", t.title)
        put("type", t.type)
        put("day", t.day)
        put("minutes", t.minutes)
        put("place", t.place)
        put("notes", t.notes)
        put("remind", t.remind)
        put("done", if (t.done) 1 else 0)
    }

    fun save(t: Task): Long {
        if (t.id == 0L) {
            t.id = writableDatabase.insert("tasks", null, values(t))
        } else {
            writableDatabase.update("tasks", values(t), "id=?", arrayOf(t.id.toString()))
        }
        return t.id
    }

    fun delete(id: Long) {
        writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
    }

    fun setDone(id: Long, done: Boolean) {
        val cv = ContentValues().apply { put("done", if (done) 1 else 0) }
        writableDatabase.update("tasks", cv, "id=?", arrayOf(id.toString()))
    }

    fun get(id: Long): Task? = query("id=?", arrayOf(id.toString())).firstOrNull()

    private fun query(where: String?, args: Array<String>?, order: String = ORDER): List<Task> {
        val out = ArrayList<Task>()
        readableDatabase.query("tasks", null, where, args, null, null, order).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Task(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
                        type = c.getInt(c.getColumnIndexOrThrow("type")),
                        day = c.getLong(c.getColumnIndexOrThrow("day")),
                        minutes = c.getInt(c.getColumnIndexOrThrow("minutes")),
                        place = c.getString(c.getColumnIndexOrThrow("place")) ?: "",
                        notes = c.getString(c.getColumnIndexOrThrow("notes")) ?: "",
                        remind = c.getInt(c.getColumnIndexOrThrow("remind")),
                        done = c.getInt(c.getColumnIndexOrThrow("done")) == 1
                    )
                )
            }
        }
        return out
    }

    /** Wszystkie wpisy danego dnia (zrobione i niezrobione). */
    fun byDay(day: Long) = query("day=?", arrayOf(day.toString()))

    /** Niezrobione – łącznie z zaległymi. */
    fun pending() = query("done=0", null)

    /** Dziś: zaległe niezrobione + wszystko z dzisiaj. */
    fun todayView(today: Long) =
        query("(done=0 AND day<?) OR day=?", arrayOf(today.toString(), today.toString()))

    /** Zrobione – od najnowszych. */
    fun doneList() = query("done=1", null, "day DESC, minutes DESC, id DESC")

    /** Widget: zaległe + najbliższe dni niezrobione, a dzisiejsze także zrobione (przekreślone). */
    fun forWidget(today: Long, until: Long) = query(
        "(done=0 AND day<=?) OR (done=1 AND day=?)",
        arrayOf(until.toString(), today.toString())
    )

    fun withReminders() = query("done=0 AND remind>=0", null)

    fun daysWithEntries(from: Long, to: Long): List<Long> {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT day FROM tasks WHERE day>=? AND day<=? ORDER BY day",
            arrayOf(from.toString(), to.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }
}
