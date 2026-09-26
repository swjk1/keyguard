package com.keyguard.app.family

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.keyguard.app.ParentActivity
import com.keyguard.app.R
import com.keyguard.app.ui.CategoryLabels

/**
 * Tells a parent that something serious was reported, without telling them what was written.
 *
 * The counterpart to [SupervisionNotice], and the opposite kind of notification in every
 * respect. That one is a standing fact at low importance that must never be an interruption;
 * this one is an interruption and nothing else, so it is high importance, it dismisses on tap,
 * and it exists only for [ParentAlerts.ALERT_FLOOR].
 *
 * ### What it is allowed to say
 *
 * A device label, a count, and a category. No message text, no host app, no excerpt — the same
 * line [SupervisionEvent] draws, and it has to be drawn again here because a notification is
 * read on a lock screen, over someone's shoulder, by whoever is holding the phone. Everything
 * beyond "something of this kind happened" is behind opening the app, which is where the child
 * knows their parent is looking.
 *
 * The wording is deliberately not alarming. "A warning was reported" is what happened; "your
 * child is in danger" is an inference the product has no basis for and a parent cannot act on
 * at the moment they read it.
 */
object ParentAlertNotice {

    private const val CHANNEL_ID = "parent_alerts"
    private const val NOTIFICATION_ID = 1002

    fun show(context: Context, digest: ParentAlerts.Digest) {
        if (!digest.shouldNotify) return
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, ParentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title(context, digest))
            .setContentText(context.getString(R.string.parent_alert_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        // Throws without the runtime permission on API 33+. Swallowed for the same reason the
        // supervision notice swallows it: a parent who declined notifications still has the
        // dashboard, and a safety tool must not crash over a notification it could not post.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Three shapes, because the useful fact changes with the number of children involved.
     *
     * With one child and one event the category is the most informative thing available and
     * fits; past that it stops fitting and a count is more honest than naming one category and
     * silently dropping the others.
     */
    private fun title(context: Context, digest: ParentAlerts.Digest): String {
        val only = digest.alerting.singleOrNull()
        return when {
            only != null && only.count == 1 -> context.getString(
                R.string.parent_alert_title_single,
                only.childLabel,
                context.getString(CategoryLabels.res(only.category)),
            )

            only != null -> context.resources.getQuantityString(
                R.plurals.parent_alert_title_child,
                only.count,
                only.childLabel,
                only.count,
            )

            else -> context.resources.getQuantityString(
                R.plurals.parent_alert_title_many,
                digest.total,
                digest.total,
                digest.alerting.size,
            )
        }
    }

    fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.parent_alert_channel_name),
            // High, unlike the supervision notice. This is the one thing in the product worth
            // interrupting for, and it only ever fires at the top severity. A parent who
            // disagrees can turn the channel down; a parent who missed it because it arrived
            // silently has been failed by the feature's only job.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.parent_alert_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
