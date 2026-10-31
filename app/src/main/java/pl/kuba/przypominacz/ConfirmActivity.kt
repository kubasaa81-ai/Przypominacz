package pl.kuba.przypominacz

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/** Okienko z widgetu: „Czy wykonano?” – odhacza dopiero po potwierdzeniu. */
class ConfirmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        if (Cats.all().isEmpty()) Cats.reload(this)

        val id = intent.getLongExtra("id", -1L)
        val t = if (id > 0) Db.get(this).get(id) else null
        if (t == null) {
            finish()
            return
        }
        setContentView(R.layout.activity_confirm)

        findViewById<TextView>(R.id.c_question).text =
            if (t.done) "Cofnąć odhaczenie?" else "Czy wykonano?"
        findViewById<View>(R.id.c_bar).setBackgroundColor(Fmt.typeColor(t.type))
        findViewById<TextView>(R.id.c_title).text = Fmt.typeEmoji(t.type) + " " + t.title
        findViewById<TextView>(R.id.c_sub).text = buildString {
            append(Fmt.dayLabel(t.day)).append(", ").append(Fmt.time(t.minutes))
            if (t.place.isNotBlank()) append("\n📍 ").append(t.place)
        }

        val yes = findViewById<MaterialButton>(R.id.c_yes)
        if (t.done) {
            yes.text = "↩  Nie wykonane"
            yes.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFEF6C00.toInt())
        }
        yes.setOnClickListener {
            Reminders.setDone(this, t.id, !t.done)
            Toast.makeText(
                this,
                if (t.done) "Cofnięto odhaczenie" else "Odhaczono ✓",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }

        findViewById<MaterialButton>(R.id.c_open).setOnClickListener {
            startActivity(
                Intent(this, EditActivity::class.java)
                    .putExtra("id", t.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        }
        findViewById<MaterialButton>(R.id.c_cancel).setOnClickListener { finish() }
    }
}
