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

class TasksWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(ctx, mgr, it) }
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TOGGLE) {
            val id = intent.getLongExtra("id", -1L)
            if (id < 0) return
            val t = Db.get(ctx).get(id) ?: return
            Reminders.setDone(ctx, id, !t.done)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "pl.kuba.przypominacz.TOGGLE"
        const val DAYS_AHEAD = 30

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, TasksWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { update(ctx, mgr, it) }
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.w_list)
        }

        @Suppress("DEPRECATION")
        fun update(ctx: Context, mgr: AppWidgetManager, wid: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setTextViewText(R.id.w_date, Fmt.headerDate())

            val svc = Intent(ctx, WidgetService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid)
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
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    class Factory(private val ctx: Context) : RemoteViewsFactory {
        private class Row(val header: String?, val task: Task?)
        private var rows: List<Row> = emptyList()

        override fun onCreate() {}
        override fun onDestroy() {}

        override fun onDataSetChanged() {
            val today = Fmt.today()
            val list = Db.get(ctx).forWidget(today, today + TasksWidget.DAYS_AHEAD)
            val out = ArrayList<Row>()
            val overdue = list.filter { it.day < today }
            if (overdue.isNotEmpty()) {
                out.add(Row("⚠ Zaległe", null))
                overdue.forEach { out.add(Row(null, it)) }
            }
            var lastDay = Long.MIN_VALUE
            list.filter { it.day >= today }.forEach {
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
            }
            rv.setTextViewText(R.id.wi_sub, sub)

            rv.setOnClickFillInIntent(R.id.wi_root, Intent().putExtra("id", t.id))
            return rv
        }
    }
}
