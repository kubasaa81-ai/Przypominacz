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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.time.LocalDate
import java.time.ZoneId

class MainActivity : AppCompatActivity() {

    private var mode = 0                 // 0 Nadchodzące, 1 Dziś, 2 Kalendarz, 3 Zrobione
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

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.m_date).text = Fmt.headerDate()
        load()
        TasksWidget.refresh(this)
    }

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
        val noTasks = tasks.isEmpty()
        empty.visibility = if (noTasks) View.VISIBLE else View.GONE
        empty.text = when (mode) {
            0 -> "Nic nie czeka na zrobienie 🎉\nDotknij „Dodaj”, żeby zapisać wizytę lub zadanie."
            1 -> "Na dziś nic nie masz zaplanowanego."
            2 -> "W tym dniu nic nie masz."
            else -> "Brak zrobionych wpisów."
        }
        if (mode == 2 && noTasks) {
            empty.visibility = View.VISIBLE
        }
    }
}
