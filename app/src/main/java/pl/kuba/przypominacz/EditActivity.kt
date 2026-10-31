package pl.kuba.przypominacz

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.time.LocalTime
import kotlin.math.abs

class EditActivity : AppCompatActivity() {

    private var task = Task()
    private var hasTime = true
    private var lastTime = 9 * 60

    private lateinit var typeButtons: List<MaterialButton>
    private lateinit var titleLayout: TextInputLayout
    private lateinit var titleEdit: TextInputEditText
    private lateinit var placeEdit: TextInputEditText
    private lateinit var notesEdit: TextInputEditText
    private lateinit var dateBtn: MaterialButton
    private lateinit var timeBtn: MaterialButton
    private lateinit var noTime: MaterialSwitch
    private lateinit var remind: Spinner
    private lateinit var dayHeader: TextView
    private lateinit var dayRows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        typeButtons = listOf(
            findViewById<MaterialButton>(R.id.e_type_task),
            findViewById<MaterialButton>(R.id.e_type_doctor),
            findViewById<MaterialButton>(R.id.e_type_event)
        )
        titleLayout = findViewById(R.id.e_title_layout)
        titleEdit = findViewById(R.id.e_title)
        placeEdit = findViewById(R.id.e_place)
        notesEdit = findViewById(R.id.e_notes)
        dateBtn = findViewById(R.id.e_date)
        timeBtn = findViewById(R.id.e_time)
        noTime = findViewById(R.id.e_no_time)
        remind = findViewById(R.id.e_remind)
        dayHeader = findViewById(R.id.e_day_header)
        dayRows = findViewById(R.id.e_day_rows)

        remind.adapter = ArrayAdapter(this, R.layout.spinner_item, Fmt.REMIND_LABELS).also {
            it.setDropDownViewResource(R.layout.spinner_item)
        }

        val id = intent.getLongExtra("id", 0L)
        val loaded = if (id > 0) Db.get(this).get(id) else null
        val deleteBtn = findViewById<MaterialButton>(R.id.e_delete)

        if (loaded != null) {
            task = loaded
            findViewById<TextView>(R.id.e_header).text = "Edycja wpisu"
            hasTime = task.minutes >= 0
            if (hasTime) lastTime = task.minutes
            titleEdit.setText(task.title)
            placeEdit.setText(task.place)
            notesEdit.setText(task.notes)
            remind.setSelection(Fmt.remindIndex(task.remind))
            deleteBtn.visibility = View.VISIBLE
            if (task.fromGoogle) findViewById<TextView>(R.id.e_google_note).visibility = View.VISIBLE
        } else {
            task.day = intent.getLongExtra("day", Fmt.today())
            task.type = Task.TYPE_TASK
            lastTime = if (task.day == Fmt.today()) {
                val next = LocalTime.now().hour + 1
                if (next in 6..22) next * 60 else 9 * 60
            } else 9 * 60
            task.minutes = lastTime
            task.remind = 60
            remind.setSelection(Fmt.remindIndex(60))
            deleteBtn.visibility = View.GONE
        }

        typeButtons.forEachIndexed { i, b ->
            b.setOnClickListener {
                task.type = i
                if (i == Task.TYPE_DOCTOR && remind.selectedItemPosition == Fmt.remindIndex(60)) {
                    remind.setSelection(Fmt.remindIndex(1440))   // do lekarza – dzień wcześniej
                }
                styleTypes()
            }
        }

        noTime.isChecked = !hasTime
        noTime.setOnCheckedChangeListener { _, checked ->
            hasTime = !checked
            refreshDateTime()
        }

        dateBtn.setOnClickListener { pickDate() }
        timeBtn.setOnClickListener { pickTime() }

        findViewById<MaterialButton>(R.id.e_save).setOnClickListener { trySave() }
        findViewById<MaterialButton>(R.id.e_cancel).setOnClickListener { finish() }
        deleteBtn.setOnClickListener { confirmDelete() }

        styleTypes()
        refreshDateTime()
    }

    private fun styleTypes() {
        typeButtons.forEachIndexed { i, b ->
            val c = Fmt.typeColor(i)
            if (i == task.type) {
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

    private fun currentMinutes() = if (hasTime) lastTime else -1

    private fun refreshDateTime() {
        dateBtn.text = "📆  " + Fmt.fullDate(task.day)
        timeBtn.text = if (hasTime) "🕒  " + Fmt.time(lastTime) else "🕒  cały dzień"
        timeBtn.isEnabled = hasTime
        timeBtn.alpha = if (hasTime) 1f else 0.5f
        refreshDayPanel()
    }

    private fun pickDate() {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Wybierz dzień")
            .setSelection(task.day * 86_400_000L)
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            task.day = sel / 86_400_000L
            refreshDateTime()
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun pickTime() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(lastTime / 60)
            .setMinute(lastTime % 60)
            .setTitleText("Wybierz godzinę")
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .build()
        picker.addOnPositiveButtonClickListener {
            lastTime = picker.hour * 60 + picker.minute
            refreshDateTime()
        }
        picker.show(supportFragmentManager, "time")
    }

    /** Kolizja z innym wpisem: 2 = ta sama godzina, 1 = blisko, 0 = brak. */
    private fun clash(other: Task, myMin: Int): Int {
        if (myMin < 0 || other.minutes < 0 || other.done) return 0
        val diff = abs(other.minutes - myMin)
        return when {
            diff == 0 -> 2
            diff <= Fmt.NEAR_MINUTES -> 1
            else -> 0
        }
    }

    private fun othersThatDay() = Db.get(this).byDay(task.day).filter { it.id != task.id }

    /** Panel „W tym dniu masz już…” – widoczny od razu przy dodawaniu. */
    private fun refreshDayPanel() {
        val others = othersThatDay()
        val my = currentMinutes()
        dayRows.removeAllViews()
        dayHeader.text = "W tym dniu masz już:"

        if (others.isEmpty()) {
            dayRows.addView(row("✓ Nic więcej nie masz zaplanowanego w tym dniu.", null, Fmt.GREEN, 0xFFE8F5E9.toInt()))
            return
        }

        val sameCount = others.count { clash(it, my) == 2 }
        val nearCount = others.count { clash(it, my) == 1 }
        if (sameCount > 0 || nearCount > 0) {
            val msg = buildString {
                append("⚠ ")
                if (sameCount > 0) append("O tej samej godzinie: $sameCount")
                if (sameCount > 0 && nearCount > 0) append(" · ")
                if (nearCount > 0) append("W pobliżu (±${Fmt.NEAR_MINUTES} min): $nearCount")
            }
            dayRows.addView(row(msg, null, if (sameCount > 0) Fmt.RED else Fmt.ORANGE, 0xFFFFF3E0.toInt()))
        }

        others.forEach { o ->
            val c = clash(o, my)
            val main = Fmt.time(o.minutes) + "   " + Fmt.typeEmoji(o.type) + " " + o.title +
                (if (o.done) "  (zrobione)" else "")
            val note = when (c) {
                2 -> "TA SAMA GODZINA!"
                1 -> {
                    val d = o.minutes - my
                    if (d > 0) "$d min po nowym wpisie" else "${-d} min przed nowym wpisem"
                }
                else -> if (o.place.isNotBlank()) "📍 " + o.place else null
            }
            val (color, bg) = when (c) {
                2 -> Fmt.RED to 0xFFFFEBEE.toInt()
                1 -> Fmt.ORANGE to 0xFFFFF3E0.toInt()
                else -> Fmt.typeColor(o.type) to 0xFFF5F7FB.toInt()
            }
            dayRows.addView(row(main, note, color, bg))
        }
    }

    private fun row(main: String, note: String?, color: Int, bg: Int): View {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = (6 * dp).toInt() }
        }
        val bar = View(this).apply {
            setBackgroundColor(color)
            layoutParams = LinearLayout.LayoutParams((5 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = main
            textSize = 15f
            setTextColor(0xFF212121.toInt())
        })
        if (note != null) {
            texts.addView(TextView(this).apply {
                text = note
                textSize = 13f
                setTextColor(color)
                setTypeface(typeface, Typeface.BOLD)
            })
        }
        box.addView(bar)
        box.addView(texts)
        return box
    }

    private fun trySave() {
        val title = titleEdit.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            titleLayout.error = "Wpisz, czego dotyczy wpis"
            titleEdit.requestFocus()
            return
        }
        titleLayout.error = null

        val my = currentMinutes()
        val clashes = othersThatDay().filter { clash(it, my) > 0 }
        if (clashes.isNotEmpty()) {
            val list = clashes.joinToString("\n") {
                "• " + Fmt.time(it.minutes) + "  " + it.title +
                    if (clash(it, my) == 2) "  (ta sama godzina)" else ""
            }
            AlertDialog.Builder(this)
                .setTitle("⚠ Kolizja terminów")
                .setMessage("W tym dniu w pobliżu tej godziny masz już:\n\n$list\n\nZapisać mimo to?")
                .setPositiveButton("Zapisz mimo to") { _, _ -> save(title) }
                .setNegativeButton("Zmienię godzinę", null)
                .show()
        } else {
            save(title)
        }
    }

    private fun save(title: String) {
        task.title = title
        task.minutes = currentMinutes()
        task.place = placeEdit.text?.toString()?.trim().orEmpty()
        task.notes = notesEdit.text?.toString()?.trim().orEmpty()
        task.remind = Fmt.REMIND_VALUES[remind.selectedItemPosition]
        Db.get(this).save(task)
        if (task.done) Reminders.cancel(this, task.id) else Reminders.schedule(this, task)
        TasksWidget.refresh(this)
        Toast.makeText(this, "Zapisano ✓", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Usunąć wpis?")
            .setMessage(task.title)
            .setPositiveButton("Usuń") { _, _ ->
                Reminders.cancel(this, task.id)
                if (task.fromGoogle) CalendarImport.skip(this, task.ext)
                Db.get(this).delete(task.id)
                TasksWidget.refresh(this)
                Toast.makeText(this, "Usunięto", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }
}
