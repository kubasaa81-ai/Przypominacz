package pl.kuba.przypominacz

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Lokalna baza SQLite – wszystko zostaje w telefonie, bez chmury. */
class Db private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "przypominacz.db", null, 3) {

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
                "done INTEGER NOT NULL DEFAULT 0," +
                "ext TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX idx_day ON tasks(day)")
        db.execSQL("CREATE INDEX idx_ext ON tasks(ext)")
        createCategoriesAndDict(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN ext TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_ext ON tasks(ext)")
        }
        if (oldVersion < 3) createCategoriesAndDict(db)
    }

    private fun createCategoriesAndDict(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS categories(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "emoji TEXT NOT NULL," +
                "color INTEGER NOT NULL," +
                "sort INTEGER NOT NULL DEFAULT 5)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS dict(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "cat INTEGER NOT NULL," +
                "text TEXT NOT NULL," +
                "place TEXT NOT NULL DEFAULT ''," +
                "UNIQUE(cat, text))"
        )
        // Rodzaje startowe (0–2 zgodne z wcześniejszymi wersjami aplikacji)
        val seed = listOf(
            Category(0, "Zadanie", "✅", 0xFF1E88E5.toInt(), 0),
            Category(1, "Lekarz", "🩺", 0xFF00897B.toInt(), 1),
            Category(3, "Spotkanie", "🤝", 0xFFEF6C00.toInt(), 2),
            Category(4, "Zajęcia", "🎓", 0xFFD81B60.toInt(), 3),
            Category(2, "Inne", "📅", 0xFF8E24AA.toInt(), 99)
        )
        seed.forEach { c ->
            db.insertWithOnConflict("categories", null, ContentValues().apply {
                put("id", c.id)
                put("name", c.name)
                put("emoji", c.emoji)
                put("color", c.color)
                put("sort", c.sort)
            }, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    // ---------- Rodzaje ----------

    fun categories(): List<Category> {
        val out = ArrayList<Category>()
        readableDatabase.rawQuery(
            "SELECT id, name, emoji, color, sort FROM categories ORDER BY sort ASC, id ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Category(c.getInt(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4)))
            }
        }
        return out
    }

    fun saveCategory(c: Category): Int {
        val cv = ContentValues().apply {
            put("name", c.name)
            put("emoji", c.emoji)
            put("color", c.color)
            put("sort", c.sort)
        }
        if (c.id < 0) {
            c.id = writableDatabase.insert("categories", null, cv).toInt()
        } else {
            writableDatabase.update("categories", cv, "id=?", arrayOf(c.id.toString()))
        }
        return c.id
    }

    /** Usuwa rodzaj: jego wpisy przechodzą do „Inne”, słownik znika. */
    fun deleteCategory(id: Int) {
        if (id in 0..2) return
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.execSQL("UPDATE tasks SET type=? WHERE type=?", arrayOf<Any>(Task.TYPE_EVENT, id))
            w.delete("dict", "cat=?", arrayOf(id.toString()))
            w.delete("categories", "id=?", arrayOf(id.toString()))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // ---------- Słowniki ----------

    private fun dictQuery(where: String?, args: Array<String>?): List<DictItem> {
        val out = ArrayList<DictItem>()
        readableDatabase.query("dict", arrayOf("id", "cat", "text", "place"), where, args, null, null,
            "text COLLATE NOCASE ASC").use { c ->
            while (c.moveToNext()) out.add(DictItem(c.getLong(0), c.getInt(1), c.getString(2), c.getString(3) ?: ""))
        }
        return out
    }

    fun dict(cat: Int) = dictQuery("cat=?", arrayOf(cat.toString()))

    fun dictAll() = dictQuery(null, null)

    /** Zwraca false, jeśli taki wpis już jest w słowniku tego rodzaju (wtedy aktualizuje miejsce). */
    fun addDict(cat: Int, text: String, place: String): Boolean {
        val cv = ContentValues().apply {
            put("cat", cat)
            put("text", text)
            put("place", place)
        }
        val id = writableDatabase.insertWithOnConflict("dict", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (id == -1L) {
            if (place.isNotBlank()) {
                writableDatabase.update("dict", ContentValues().apply { put("place", place) },
                    "cat=? AND text=?", arrayOf(cat.toString(), text))
            }
            return false
        }
        return true
    }

    fun deleteDict(id: Long) {
        writableDatabase.delete("dict", "id=?", arrayOf(id.toString()))
    }

    private fun values(t: Task) = ContentValues().apply {
        put("title", t.title)
        put("type", t.type)
        put("day", t.day)
        put("minutes", t.minutes)
        put("place", t.place)
        put("notes", t.notes)
        put("remind", t.remind)
        put("done", if (t.done) 1 else 0)
        put("ext", t.ext)
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

    fun byExt(ext: String): Task? = query("ext=?", arrayOf(ext)).firstOrNull()

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
                        done = c.getInt(c.getColumnIndexOrThrow("done")) == 1,
                        ext = c.getString(c.getColumnIndexOrThrow("ext")) ?: ""
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

    /**
     * Widget – wybrany miesiąc.
     * Bieżący miesiąc: zaległe niezrobione + cały miesiąc (zrobione zostają, przekreślone,
     * żeby dało się je cofnąć prosto z widgetu).
     * Inny miesiąc: wszystkie wpisy z tego miesiąca.
     */
    fun widgetMonth(from: Long, to: Long, today: Long, current: Boolean): List<Task> =
        if (current) {
            query(
                "done=0 AND (day<? OR (day>=? AND day<=?))",
                arrayOf(today.toString(), today.toString(), to.toString())
            )
        } else {
            query("done=0 AND day>=? AND day<=?", arrayOf(from.toString(), to.toString()))
        }

    /** Ile wpisów zostało do zrobienia w danym okresie. */
    fun countPending(from: Long, to: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tasks WHERE done=0 AND day>=? AND day<=?",
            arrayOf(from.toString(), to.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    fun countBetween(from: Long, to: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM tasks WHERE day>=? AND day<=?",
            arrayOf(from.toString(), to.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /** Przyszłe wpisy pobrane z Kalendarza Google (do synchronizacji). */
    fun importedFrom(today: Long) = query("ext LIKE 'g:%' AND day>=?", arrayOf(today.toString()))

    /** Wpisy utworzone w aplikacji (nie z Google), od dziś, niezrobione. */
    fun localFuture(today: Long) = query("ext='' AND done=0 AND day>=?", arrayOf(today.toString()))

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
