package pl.kuba.przypominacz

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return

        if (intent.action == Reminders.ACTION_DONE) {
            Reminders.setDone(ctx, id, true)
            return
        }

        val t = Db.get(ctx).get(id) ?: return
        if (t.done) return
        Reminders.createChannel(ctx)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            ctx, id.toInt(),
            Intent(ctx, EditActivity::class.java)
                .putExtra("id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val done = PendingIntent.getBroadcast(
            ctx, 100000 + id.toInt(),
            Intent(ctx, AlarmReceiver::class.java)
                .setAction(Reminders.ACTION_DONE)
                .putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val whenTxt = Fmt.dayLabel(t.day) + ", " + Fmt.time(t.minutes)
        val text = buildString {
            append(whenTxt)
            if (t.place.isNotBlank()) append(" · ").append(t.place)
        }
        val big = buildString {
            append(text)
            if (t.notes.isNotBlank()) append("\n").append(t.notes)
        }

        val n = NotificationCompat.Builder(ctx, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(Fmt.typeColor(t.type))
            .setContentTitle(Fmt.typeEmoji(t.type) + " " + t.title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "✓ Zrobione", done)
            .build()

        try {
            NotificationManagerCompat.from(ctx).notify(id.toInt(), n)
        } catch (e: SecurityException) {
            // brak zgody na powiadomienia
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx)
        TasksWidget.refresh(ctx)
    }
}
