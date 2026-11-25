package ng.ainow.jarvis.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Android won't let Jarvis open any screen from the background while its accessibility service is
 * off, not even its own. A notification the user taps is allowed, so Jarvis posts one:
 *   tap the notification -> the Accessibility and permissions section in Jarvis
 *   tap "Turn on"        -> Android's accessibility settings directly
 * It is removed once the accessibility service connects.
 */
object SetupNotice {
    const val EXTRA_NEED_ACCESSIBILITY = "needAccessibility"
    private const val CHANNEL = "jarvis_setup"
    private const val ID = 2

    /** The Jarvis screen, brought to the front (or opened). */
    fun openJarvis(ctx: Context, needAccessibility: Boolean = false): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_NEED_ACCESSIBILITY, needAccessibility)
        return PendingIntent.getActivity(
            ctx, if (needAccessibility) 1 else 0, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun show(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        // High importance so it pops up on screen and the screen reader reads it out.
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis setup", NotificationManager.IMPORTANCE_HIGH))
        val settings = PendingIntent.getActivity(
            ctx, 2,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("Jarvis needs accessibility turned on")
            .setContentText("Tap to set up Jarvis so it can open apps for you.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openJarvis(ctx, needAccessibility = true))
            .addAction(Notification.Action.Builder(null, "Turn on", settings).build())
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(ID, n)
        } catch (e: SecurityException) {
            // Notifications not allowed: the spoken message is all we can do.
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}
