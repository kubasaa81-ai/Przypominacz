package pl.kuba.przypominacz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.CalendarView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var mode = 0                 // 0 Do zrobienia, 1 Dziś, 2 Kalendarz, 3 Zrobione
    private var calDay = Fmt.today()

    private lateinit var tabs: List<MaterialButton>
    private val tabColors = intArrayOf(
        0xFF1E88E5.toInt(), 0xFFEF6C00.toInt(), 0xFF8E24AA.toInt(), 0xFF2E7D32.toInt()
    )
    private lateinit var calendarBox: View
    private lateinit var calendar: CalendarView
    private lateinit var calInfo: TextView
    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var adapter: TaskAdapter
    private lateinit var googleBtn: MaterialButton

    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var importing = false

    companion object {
        private const val REQ_NOTIF = 1
        private const val REQ_CALENDAR = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Reminders.createChannel(this)

        findViewById<TextView>(R.id.m_date).text = Fmt.headerDate()

        tabs = listOf(
            findViewById<MaterialButton>(R.id.tab_upcoming),
            findViewById<MaterialButton>(R.id.tab_today),
            findViewById<MaterialButton>(R.id.tab_calendar),
            findViewById<MaterialButton>(R.id.tab_done)
        )
        tabs.forEachIndexed { i, b -> b.setOnClickListener { mode = i; load() } }

        calendarBox = findViewById(R.id.m_calendar_box)
        calendar = findViewById(R.id.m_calendar)
        calInfo = findViewById(R.id.m_cal_info)
        calendar.setOnDateChangeListener { _, y, m, d ->
            calDay = LocalDate.of(y, m + 1, d).toEpochDay()
            load()
        }

        list = findViewById(R.id.m_list)
        empty = findViewById(R.id.m_empty)
        adapter = TaskAdapter(
            onOpen = { t -> startActivity(Intent(this, EditActivity::class.java).putExtra("id", t.id)) },
            onToggle = { t, checked ->
                Reminders.setDone(this, t.id, checked)
                list.post { load() }
            }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<ExtendedFloatingActionButton>(R.id.m_add).setOnClickListener {
            val day = if (mode == 2) calDay else Fmt.today()
            startActivity(Intent(this, EditActivity::class.java).putExtra("day", day))
        }

        googleBtn = findViewById(R.id.m_google)
        googleBtn.setOnClickListener { startGoogleImport() }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.m_date).text = Fmt.headerDate()
        load()
        TasksWidget.refresh(this)
        // Automatyczne odświeżenie wydarzeń z Google przy każdym otwarciu
        if (CalendarImport.hasPermission(this) && CalendarImport.selected(this).isNotEmpty()) {
            runImport(CalendarImport.selected(this), silent = true)
        }
        updateGoogleButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun updateGoogleButton() {
        val on = CalendarImport.hasPermission(this) && CalendarImport.selected(this).isNotEmpty()
        googleBtn.text = if (on) "🔄  Kalendarz Google: włączony" else "📥  Pobierz z Kalendarza Google"
    }

    // ---------- Kalendarz Google ----------

    private fun startGoogleImport() {
        if (CalendarImport.hasPermission(this)) {
            chooseCalendars()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CALENDAR) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                chooseCalendars()
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Brak dostępu do kalendarza")
                    .setMessage(
                        "Bez zgody na odczyt kalendarza nie da się pobrać wydarzeń.\n\n" +
                            "Możesz ją włączyć w: Ustawienia → Aplikacje → Przypominacz → Uprawnienia → Kalendarz."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun chooseCalendars() {
        val cals = CalendarImport.calendars(this)
        if (cals.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Nie znaleziono kalendarzy")
                .setMessage(
                    "Telefon nie ma zsynchronizowanego żadnego kalendarza.\n\n" +
                        "Sprawdź w Ustawienia → Konta i kopia zapasowa → Zarządzaj kontami → Google, " +
                        "czy synchronizacja Kalendarza jest włączona."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val saved = CalendarImport.selected(this)
        val labels = cals.map { c ->
            if (c.account.isNotBlank() && c.account != c.name) "${c.name}\n${c.account}" else c.name
        }.toTypedArray()
        val checked = BooleanArray(cals.size) { i ->
            if (saved.isEmpty()) cals[i].isGoogle else cals[i].id in saved
        }

        AlertDialog.Builder(this)
            .setTitle("Z których kalendarzy pobierać?")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Pobierz") { _, _ ->
                val ids = cals.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                CalendarImport.setSelected(this, ids)
                updateGoogleButton()
                if (ids.isEmpty()) {
                    Toast.makeText(this, "Pobieranie z Google wyłączone", Toast.LENGTH_SHORT).show()
                } else {
                    runImport(ids, silent = false)
                }
            }
            .setNeutralButton("Wyłącz") { _, _ ->
                CalendarImport.setSelected(this, emptySet())
                updateGoogleButton()
                Toast.makeText(this, "Pobieranie z Google wyłączone", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun runImport(ids: Set<Long>, silent: Boolean) {
        if (importing) return
        importing = true
        worker.execute {
            val res = try {
                CalendarImport.run(applicationContext, ids)
            } catch (e: Exception) {
                null
            }
            importing = false
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                load()
                if (res == null) {
                    if (!silent) Toast.makeText(this, "Nie udało się odczytać kalendarza", Toast.LENGTH_LONG).show()
                } else if (!silent) {
                    AlertDialog.Builder(this)
                        .setTitle("📥 Pobrano z Kalendarza Google")
                        .setMessage(
                            "Nowe: ${res.added}\nZaktualizowane: ${res.updated}\nUsunięte: ${res.removed}\n\n" +
                                "Wydarzenia od dziś na 12 miesięcy do przodu. " +
                                "Od teraz lista odświeża się sama przy każdym otwarciu aplikacji."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                } else if (res.added + res.updated + res.removed > 0) {
                    Toast.makeText(this, "Kalendarz Google: zaktualizowano", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ---------- Lista ----------

    private fun styleTabs() {
        tabs.forEachIndexed { i, b ->
            val c = tabColors[i]
            if (i == mode) {
                b.backgroundTintList = ColorStateList.valueOf(c)
                b.setTextColor(0xFFFFFFFF.toInt())
                b.strokeWidth = 0
            } else {
                b.backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                b.setTextColor(c)
                b.strokeColor = ColorStateList.valueOf(c)
                b.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun load() {
        styleTabs()
        val db = Db.get(this)
        val today = Fmt.today()
        calendarBox.visibility = if (mode == 2) View.VISIBLE else View.GONE

        val tasks: List<Task> = when (mode) {
            0 -> db.pending()
            1 -> db.todayView(today)
            2 -> db.byDay(calDay)
            else -> db.doneList()
        }

        val rows = ArrayList<Any>()
        if (mode == 2) {
            rows.add(Fmt.fullDate(calDay))
            rows.addAll(tasks)
            val d = LocalDate.ofEpochDay(calDay)
            val first = d.withDayOfMonth(1)
            val last = d.withDayOfMonth(d.lengthOfMonth())
            val days = db.daysWithEntries(first.toEpochDay(), last.toEpochDay())
            calInfo.text = if (days.isEmpty()) "W tym miesiącu brak wpisów."
            else "Wpisy w tym miesiącu w dni: " +
                days.joinToString(", ") { LocalDate.ofEpochDay(it).dayOfMonth.toString() }
            val ms = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (calendar.date / 86_400_000L != ms / 86_400_000L) calendar.setDate(ms, false, false)
        } else {
            val overdue = if (mode == 3) emptyList() else tasks.filter { it.day < today && !it.done }
            if (overdue.isNotEmpty()) {
                rows.add("⚠ Zaległe")
                rows.addAll(overdue)
            }
            var lastDay = Long.MIN_VALUE
            tasks.filter { it !in overdue }.forEach {
                if (it.day != lastDay) {
                    rows.add(Fmt.dayLabel(it.day))
                    lastDay = it.day
                }
                rows.add(it)
            }
        }

        adapter.submit(rows)
        empty.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when (mode) {
            0 -> "Nic nie czeka na zrobienie 🎉\nDotknij „Dodaj”, żeby zapisać wizytę lub zadanie."
            1 -> "Na dziś nic nie masz zaplanowanego."
            2 -> "W tym dniu nic nie masz."
            else -> "Brak zrobionych wpisów."
        }
    }
}
