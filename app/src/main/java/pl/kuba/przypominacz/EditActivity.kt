package pl.kuba.przypominacz

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
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

    private lateinit var typeGroup: ChipGroup
    private lateinit var dictPick: MaterialButton
    private lateinit var titleLayout: TextInputLayout
    private lateinit var titleEdit: MaterialAutoCompleteTextView
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

        if (Cats.all().isEmpty()) Cats.reload(this)
        typeGroup = findViewById(R.id.e_types)
        dictPick = findViewById(R.id.e_dict_pick)
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

        dictPick.setOnClickListener { pickFromDict() }
        findViewById<MaterialButton>(R.id.e_dict_add).setOnClickListener { addToDict() }
        titleEdit.setOnItemClickListener { parent, _, position, _ ->
            val item = parent.getItemAtPosition(position) as? DictEntry ?: return@setOnItemClickListener
            applyDict(item.item)
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

        if (Cats.all().none { it.id == task.type }) task.type = Task.TYPE_EVENT
        buildTypeChips()
        refreshDict()
        refreshDateTime()
    }

    // ---------- Rodzaje ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildTypeChips() {
        typeGroup.removeAllViews()
        Cats.all().forEach { c ->
            val chip = Chip(this)
            chip.text = c.emoji + " " + c.name
            chip.textSize = 15f
            chip.isCheckable = false
            chip.chipMinHeight = dp(42).toFloat()
            chip.setEnsureMinTouchTargetSize(true)
            if (c.id == task.type) {
                chip.chipBackgroundColor = ColorStateList.valueOf(c.color)
                chip.setTextColor(0xFFFFFFFF.toInt())
                chip.chipStrokeWidth = 0f
                chip.setTypeface(chip.typeface, Typeface.BOLD)
            } else {
                chip.chipBackgroundColor = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                chip.setTextColor(c.color)
                chip.chipStrokeColor = ColorStateList.valueOf(c.color)
                chip.chipStrokeWidth = dp(2).toFloat()
            }
            chip.setOnClickListener { selectType(c.id) }
            chip.setOnLongClickListener { editCategory(c); true }
            typeGroup.addView(chip)
        }
        val add = Chip(this)
        add.text = "＋ Nowy rodzaj"
        add.textSize = 15f
        add.isCheckable = false
        add.chipMinHeight = dp(42).toFloat()
        add.chipBackgroundColor = ColorStateList.valueOf(0xFF546E7A.toInt())
        add.setTextColor(0xFFFFFFFF.toInt())
        add.chipStrokeWidth = 0f
        add.setOnClickListener { editCategory(null) }
        typeGroup.addView(add)
    }

    private fun selectType(id: Int) {
        task.type = id
        if (id == Task.TYPE_DOCTOR && remind.selectedItemPosition == Fmt.remindIndex(60)) {
            remind.setSelection(Fmt.remindIndex(1440))   // do lekarza – dzień wcześniej
        }
        buildTypeChips()
        refreshDict()
    }

    private fun editCategory(cat: Category?) {
        val c = cat?.copy() ?: Category(id = -1, name = "", emoji = "📌", color = Cats.PALETTE[3], sort = 50)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), 0)
        }
        val nameEdit = EditText(this).apply {
            hint = "Nazwa (np. Zajęcia dodatkowe)"
            setText(c.name)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val emojiEdit = EditText(this).apply {
            hint = "Ikonka (emoji, np. ⚽ 🎵 💼)"
            setText(c.emoji)
        }
        box.addView(nameEdit)
        box.addView(emojiEdit)
        box.addView(TextView(this).apply {
            text = "Kolor:"
            textSize = 15f
            setPadding(0, dp(12), 0, dp(6))
            setTextColor(0xFF424242.toInt())
        })

        val swatches = ArrayList<View>()
        fun paint() {
            Cats.PALETTE.forEachIndexed { i, col ->
                val g = GradientDrawable()
                g.shape = GradientDrawable.OVAL
                g.setColor(col)
                if (col == c.color) g.setStroke(dp(4), 0xFF212121.toInt())
                swatches[i].background = g
            }
        }
        Cats.PALETTE.toList().chunked(5).forEach { rowColors ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            rowColors.forEach { col ->
                val v = View(this)
                v.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also { it.marginEnd = dp(10) }
                v.setOnClickListener { c.color = col; paint() }
                swatches.add(v)
                row.addView(v)
            }
            box.addView(row)
        }
        paint()

        val b = AlertDialog.Builder(this)
            .setTitle(if (cat == null) "Nowy rodzaj" else "Zmień rodzaj")
            .setView(box)
            .setPositiveButton("Zapisz", null)
            .setNegativeButton("Anuluj", null)
        if (cat != null && !cat.builtIn) b.setNeutralButton("Usuń", null)
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameEdit.text.toString().trim()
                if (name.isEmpty()) {
                    nameEdit.error = "Wpisz nazwę"
                    return@setOnClickListener
                }
                c.name = name
                c.emoji = emojiEdit.text.toString().trim().ifEmpty { "📌" }.take(4)
                val id = Db.get(this).saveCategory(c)
                Cats.reload(this)
                TasksWidget.refresh(this)
                if (cat == null) task.type = id
                buildTypeChips()
                refreshDict()
                dlg.dismiss()
            }
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Usunąć rodzaj „${c.name}”?")
                    .setMessage("Wpisy tego rodzaju przejdą do „Inne”, a jego słownik zostanie usunięty.")
                    .setPositiveButton("Usuń") { _, _ ->
                        Db.get(this).deleteCategory(c.id)
                        Cats.reload(this)
                        TasksWidget.refresh(this)
                        if (task.type == c.id) task.type = Task.TYPE_EVENT
                        buildTypeChips()
                        refreshDict()
                        dlg.dismiss()
                    }
                    .setNegativeButton("Anuluj", null)
                    .show()
            }
        }
        dlg.show()
    }

    // ---------- Słownik ----------

    /** Pozycja na liście podpowiedzi – toString() to tekst wstawiany do pola. */
    private class DictEntry(val item: DictItem) {
        override fun toString() = item.text
    }

    private fun refreshDict() {
        val items = Db.get(this).dict(task.type)
        val cat = Cats.byId(task.type)
        dictPick.text = "📖 Ze słownika\n" + cat.name + " (" + items.size + ")"
        titleEdit.setAdapter(ArrayAdapter(this, R.layout.spinner_item, items.map { DictEntry(it) }))
    }

    private fun applyDict(d: DictItem) {
        titleEdit.setText(d.text, false)
        titleEdit.setSelection(titleEdit.text?.length ?: 0)
        if (d.place.isNotBlank() && placeEdit.text.isNullOrBlank()) placeEdit.setText(d.place)
        titleLayout.error = null
    }

    private fun pickFromDict() {
        val cat = Cats.byId(task.type)
        val items = Db.get(this).dict(task.type)
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("📖 Słownik: " + cat.name)
                .setMessage(
                    "Ten słownik jest jeszcze pusty.\n\nWpisz tytuł (i ewentualnie miejsce), " +
                        "a potem dotknij „➕ Do słownika” – następnym razem wybierzesz go z listy."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = items.map { if (it.place.isBlank()) it.text else it.text + "\n📍 " + it.place }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(cat.emoji + " Słownik: " + cat.name)
            .setItems(labels) { _, which -> applyDict(items[which]) }
            .setNeutralButton("🗑 Usuń pozycje") { _, _ -> deleteFromDict(items) }
            .setNegativeButton("Zamknij", null)
            .show()
    }

    private fun deleteFromDict(items: List<DictItem>) {
        val checked = BooleanArray(items.size)
        AlertDialog.Builder(this)
            .setTitle("Zaznacz pozycje do usunięcia")
            .setMultiChoiceItems(items.map { it.text }.toTypedArray(), checked) { _, w, c -> checked[w] = c }
            .setPositiveButton("Usuń") { _, _ ->
                var n = 0
                items.forEachIndexed { i, d -> if (checked[i]) { Db.get(this).deleteDict(d.id); n++ } }
                refreshDict()
                Toast.makeText(this, "Usunięto ze słownika: $n", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    private fun addToDict() {
        val text = titleEdit.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            titleLayout.error = "Najpierw wpisz tytuł, który chcesz zapisać w słowniku"
            titleEdit.requestFocus()
            return
        }
        titleLayout.error = null
        val place = placeEdit.text?.toString()?.trim().orEmpty()
        val cat = Cats.byId(task.type)
        val added = Db.get(this).addDict(task.type, text, place)
        refreshDict()
        Toast.makeText(
            this,
            if (added) "Dodano do słownika „${cat.name}”" else "To już jest w słowniku „${cat.name}”",
            Toast.LENGTH_SHORT
        ).show()
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
        val before = task.copy()
        task.title = title
        task.minutes = currentMinutes()
        task.place = placeEdit.text?.toString()?.trim().orEmpty()
        task.notes = notesEdit.text?.toString()?.trim().orEmpty()
        task.remind = Fmt.REMIND_VALUES[remind.selectedItemPosition]

        // Synchronizacja z Kalendarzem Google
        var msg = "Zapisano ✓"
        if (task.ext.isEmpty()) {
            if (CalendarImport.writesToGoogle(this)) {
                msg = if (CalendarImport.pushNew(this, task)) "Zapisano ✓ i dodano do Kalendarza Google"
                else "Zapisano ✓ (nie udało się dodać do Google)"
            }
        } else if (task.fromGoogle) {
            val changed = before.title != task.title || before.day != task.day ||
                before.minutes != task.minutes || before.place != task.place || before.notes != task.notes
            if (changed) {
                msg = if (CalendarImport.pushUpdate(this, task)) "Zapisano ✓ i zmieniono w Kalendarzu Google"
                else "Zapisano tylko tutaj – to wydarzenie zmień w Kalendarzu Google (cykliczne lub tylko do odczytu)"
            }
        }

        Db.get(this).save(task)
        if (task.done) Reminders.cancel(this, task.id) else Reminders.schedule(this, task)
        TasksWidget.refresh(this)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun deleteHere(alsoGoogle: Boolean) {
        Reminders.cancel(this, task.id)
        var msg = "Usunięto"
        if (task.fromGoogle) {
            if (alsoGoogle && CalendarImport.deleteRemote(this, task)) {
                msg = "Usunięto tutaj i z Kalendarza Google"
            } else {
                CalendarImport.skip(this, task.ext)    // żeby nie wrócił przy następnym pobraniu
            }
        }
        Db.get(this).delete(task.id)
        TasksWidget.refresh(this)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        val b = AlertDialog.Builder(this)
            .setTitle("Usunąć wpis?")
            .setMessage(task.title)
            .setNegativeButton("Anuluj", null)
        if (CalendarImport.canWriteBack(this, task)) {
            b.setMessage(task.title + "\n\nTen wpis jest też w Kalendarzu Google.")
            b.setPositiveButton("Usuń też z Google") { _, _ -> deleteHere(alsoGoogle = true) }
            b.setNeutralButton("Tylko tutaj") { _, _ -> deleteHere(alsoGoogle = false) }
        } else {
            b.setPositiveButton("Usuń") { _, _ -> deleteHere(alsoGoogle = false) }
        }
        b.show()
    }
}
