package pl.kuba.przypominacz

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Lista z nagłówkami dni (String) i wpisami (Task). */
class TaskAdapter(
    private val onOpen: (Task) -> Unit,
    private val onToggle: (Task, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<Any> = emptyList()

    fun submit(newRows: List<Any>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position] is String) 0 else 1

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.h_text)
    }

    class TaskVH(v: View) : RecyclerView.ViewHolder(v) {
        val bar: View = v.findViewById(R.id.t_bar)
        val check: CheckBox = v.findViewById(R.id.t_check)
        val title: TextView = v.findViewById(R.id.t_title)
        val sub: TextView = v.findViewById(R.id.t_sub)
        val notes: TextView = v.findViewById(R.id.t_notes)
        val type: TextView = v.findViewById(R.id.t_type)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == 0) HeaderVH(inf.inflate(R.layout.item_header, parent, false))
        else TaskVH(inf.inflate(R.layout.item_task, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = rows[position]
        if (holder is HeaderVH) {
            val s = row as String
            holder.text.text = s
            holder.text.setTextColor(if (s.startsWith("⚠")) Fmt.RED else 0xFF3949AB.toInt())
            return
        }
        val t = row as Task
        val h = holder as TaskVH
        val color = Fmt.typeColor(t.type)
        h.bar.setBackgroundColor(color)

        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = t.done
        h.check.setOnCheckedChangeListener { _, checked -> onToggle(t, checked) }

        h.title.text = t.title
        h.title.paintFlags = if (t.done) h.title.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else h.title.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        h.title.setTextColor(if (t.done) Fmt.GRAY else 0xFF212121.toInt())

        h.sub.text = buildString {
            append("🕒 ").append(Fmt.time(t.minutes))
            if (t.place.isNotBlank()) append("   📍 ").append(t.place)
            if (t.remind >= 0 && !t.done) append("   🔔")
        }

        h.notes.visibility = if (t.notes.isBlank()) View.GONE else View.VISIBLE
        h.notes.text = t.notes

        h.type.text = Fmt.typeEmoji(t.type) + " " + Fmt.typeName(t.type) +
            if (t.fromGoogle) "  ·  z Kalendarza Google" else ""
        h.type.setTextColor(color)

        h.itemView.setOnClickListener { onOpen(t) }
    }
}
