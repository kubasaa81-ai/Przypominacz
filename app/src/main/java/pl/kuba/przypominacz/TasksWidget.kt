package pl.kuba.przypominacz

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.YearMonth

class TasksWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, mgr, it) }
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        val e = prefs(ctx).edit()
        ids.forEach { e.remove("m_$it") }
        e.apply()
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        val wid = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val id = intent.getLongExtra("id", -1L)
                if (id < 0) return
                val t = Db.get(ctx).get(id) ?: return
                Reminders.setDone(ctx, id, !t.done)
            }
            ACTION_PREV, ACTION_NEXT, ACTION_NOW -> {
                if (wid == AppWidgetManager.INVALID_APPWIDGET_ID) return
                val cur = monthOffset(ctx, wid)
                val next = when (intent.action) {
                    ACTION_PREV -> cur - 1
                    ACTION_NEXT -> cur + 1
                    else -> 0
                }
                prefs(ctx).edit().putInt("m_$wid", next).apply()
                val mgr = AppWidgetManager.getInstance(ctx)
                update(ctx, mgr, wid)
                mgr.notifyAppWidgetViewDataChanged(intArrayOf(wid), R.id.w_list)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "pl.kuba.przypominacz.TOGGLE"
        const val ACTION_PREV = "pl.kuba.przypominacz.PREV_MONTH"
        const val ACTION_NEXT = "pl.kuba.przypominacz.NEXT_MONTH"
        const val ACTION_NOW = "pl.kuba.przypominacz.THIS_MONTH"

        fun prefs(ctx: Context) = ctx.getSharedPreferences("widget", Context.MODE_PRIVATE)

        fun monthOffset(ctx: Context, wid: Int) = prefs(ctx).getInt("m_$wid", 0)

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TasksWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { update(ctx, mgr, it) }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        }

        private fun nav(ctx: Context, wid: Int, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, wid * 10 + code,
                Intent(ctx, TasksWidget::class.java)
                    .setAction(action)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

        @Suppress("DEPRECATION")
        fun update(ctx: Context, mgr: AppWidgetManager, wid: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setTextViewText(R.id.w_date, Fmt.headerDate())

            val offset = monthOffset(ctx, wid)
            val ym = YearMonth.now().plusMonths(offset.toLong())
            val count = Db.get(ctx).countBetween(ym.atDay(1).toEpochDay(), ym.atEndOfMonth().toEpochDay())
            rv.setTextViewText(R.id.w_month, Fmt.monthName(ym) + " · " + Fmt.countLabel(count))
            rv.setTextViewText(
                R.id.w_empty,
                if (offset == 0) "W tym miesiącu nic więcej 🎉\nDotknij +, aby dodać"
                else "Brak wpisów w tym miesiącu.\nDotknij +, aby dodać"
            )

            val svc = Intent(ctx, WidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid)
                .putExtra("offset", offset)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            rv.setRemoteAdapter(R.id.w_list, svc)
            rv.setEmptyView(R.id.w_list, R.id.w_empty)

            // Dotknięcie wiersza = odhacz / odznacz
            val toggle = Intent(ctx, TasksWidget::class.java).setAction(ACTION_TOGGLE)
            rv.setPendingIntentTemplate(
                R.id.w_list,
                PendingIntent.getBroadcast(
                    ctx, 0, toggle,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
            )

            rv.setOnClickPendingIntent(R.id.w_prev, nav(ctx, wid, ACTION_PREV, 3))
            rv.setOnClickPendingIntent(R.id.w_next, nav(ctx, wid, ACTION_NEXT, 4))
            rv.setOnClickPendingIntent(R.id.w_month, nav(ctx, wid, ACTION_NOW, 5))

            rv.setOnClickPendingIntent(
                R.id.w_add,
                PendingIntent.getActivity(
                    ctx, 1,
                    Intent(ctx, EditActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            val openApp = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            rv.setOnClickPendingIntent(R.id.w_title, openApp)
            rv.setOnClickPendingIntent(R.id.w_date, openApp)

            mgr.updateAppWidget(wid, rv)
        }
    }
}

class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(
            applicationContext,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        )

    class Factory(private val ctx: Context, private val wid: Int) : RemoteViewsFactory {
        private class Row(val header: String?, val task: Task?)
        private var rows: List<Row> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val today = Fmt.today()
            val offset = TasksWidget.monthOffset(ctx, wid)
            val ym = YearMonth.now().plusMonths(offset.toLong())
            val from = ym.atDay(1).toEpochDay()
            val to = ym.atEndOfMonth().toEpochDay()
            val list = Db.get(ctx).widgetMonth(from, to, today, offset == 0)

            val out = ArrayList<Row>()
            if (offset == 0) {
                val overdue = list.filter { it.day < today }
                if (overdue.isNotEmpty()) {
                    out.add(Row("⚠ Zaległe", null))
                    overdue.forEach { out.add(Row(null, it)) }
                }
            }
            var lastDay = Long.MIN_VALUE
            list.filter { offset != 0 || it.day >= today }.forEach {
                if (it.day != lastDay) {
                    out.add(Row(Fmt.dayLabel(it.day), null))
                    lastDay = it.day
                }
                out.add(Row(null, it))
            }
            rows = out
        }

        override fun getCount() = rows.size
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false
        override fun getLoadingView(): RemoteViews? = null

        override fun getViewAt(position: Int): RemoteViews {
            if (position >= rows.size) return RemoteViews(ctx.packageName, R.layout.widget_header)
            val r = rows[position]
            if (r.header != null) {
                return RemoteViews(ctx.packageName, R.layout.widget_header).apply {
                    setTextViewText(R.id.wh_text, r.header)
                    setTextColor(
                        R.id.wh_text,
                        if (r.header.startsWith("⚠")) Fmt.RED else 0xFF3949AB.toInt()
                    )
                }
            }
            val t = r.task!!
            val rv = RemoteViews(ctx.packageName, R.layout.widget_item)
            rv.setImageViewResource(R.id.wi_check, if (t.done) R.drawable.ic_checked else R.drawable.ic_box)
            rv.setInt(R.id.wi_bar, "setBackgroundColor", Fmt.typeColor(t.type))

            val title = SpannableString(Fmt.typeEmoji(t.type) + " " + t.title)
            if (t.done) {
                title.setSpan(StrikethroughSpan(), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            rv.setTextViewText(R.id.wi_title, title)
            rv.setTextColor(R.id.wi_title, if (t.done) Fmt.GRAY else 0xFF212121.toInt())

            val sub = buildString {
                append(Fmt.time(t.minutes))
                if (t.day < Fmt.today()) append(" · ").append(Fmt.shortDate(t.day))
                if (t.place.isNotBlank()) append(" · ").append(t.place)
                if (t.fromGoogle) append(" · Google")
            }
            rv.setTextViewText(R.id.wi_sub, sub)

            rv.setOnClickFillInIntent(R.id.wi_root, Intent().putExtra("id", t.id))
            return rv
        }
    }
}
