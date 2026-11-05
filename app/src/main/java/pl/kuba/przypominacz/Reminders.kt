package pl.kuba.przypominacz

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.ZoneId

object Reminders {
    const val CHANNEL = "przypomnienia_dzwiek"
    private const val OLD_CHANNEL = "przypomnienia"
    const val ACTION_ALARM = "pl.kuba.przypominacz.ALARM"
    const val ACTION_DONE = "pl.kuba.przypominacz.DONE"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Przypomnienia (z dźwiękiem)", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Przypomnienia o wizytach i zadaniach – z dźwiękiem i wibracją"
            ch.setSound(
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            ch.enableVibration(true)
            ch.vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            ch.enableLights(true)
            ch.lightColor = 0xFF43A047.toInt()
            ch.lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    /** Czy powiadomienia mają szansę zadzwonić – zgoda, kanał nie wyciszony. */
    fun soundProblem(ctx: Context): String? {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return "Powiadomienia aplikacji są wyłączone."
        val ch = nm.getNotificationChannel(CHANNEL) ?: return null
        if (ch.importance < NotificationManager.IMPORTANCE_DEFAULT) return "Kanał przypomnień jest wyciszony."
        if (ch.sound == null) return "Kanał przypomnień nie ma ustawionego dźwięku."
        return null
    }

    /** Powiadomienie próbne – żeby sprawdzić dźwięk. */
    fun showTest(ctx: Context) {
        createChannel(ctx)
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF43A047.toInt())
            .setContentTitle("🔔 Test przypomnienia")
            .setContentText("Tak będą wyglądać i brzmieć przypomnienia.")
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(999_999, n)
        } catch (e: SecurityException) {
        }
    }

    /** Moment przypomnienia w ms. Wpisy bez godziny przypominają o 9:00. */
    fun triggerAt(t: Task): Long? {
        if (t.remind < 0 || t.done) return null
        val date = LocalDate.ofEpochDay(t.day)
        val trig = if (t.minutes >= 0) {
            date.atTime(t.minutes / 60, t.minutes % 60).minusMinutes(t.remind.toLong())
        } else {
            val base = date.atTime(9, 0)
            if (t.remind >= 1440) base.minusMinutes(t.remind.toLong()) else base
        }
        val ms = trig.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return if (ms > System.currentTimeMillis()) ms else null
    }

    private fun pending(ctx: Context, id: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id.toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, t: Task) {
        cancel(ctx, t.id)
        val at = triggerAt(t) ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, t.id)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
    }

    fun rescheduleAll(ctx: Context) {
        Db.get(ctx).withReminders().forEach { schedule(ctx, it) }
    }

    /** Wspólna obsługa odhaczania – z listy, widgetu i powiadomienia. */
    fun setDone(ctx: Context, id: Long, done: Boolean) {
        val db = Db.get(ctx)
        db.setDone(id, done)
        if (done) {
            cancel(ctx, id)
            ctx.getSystemService(NotificationManager::class.java).cancel(id.toInt())
        } else {
            db.get(id)?.let { schedule(ctx, it) }
        }
        TasksWidget.refresh(ctx)
    }
}
