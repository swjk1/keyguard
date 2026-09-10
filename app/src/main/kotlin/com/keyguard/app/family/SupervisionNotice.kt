package com.keyguard.app.family

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.keyguard.app.R
import com.keyguard.app.SetupActivity

/**
 * The standing notice that this device is supervised.
 *
 * Not a nicety and not a legal box-tick either, though it is both: Play requires an app that
 * monitors another person to tell that person, persistently, and Apple's position on keyboards
 * collecting user activity is stricter still. The product reason is simpler — a child who
 * discovers by accident that their keyboard has been reporting on them will never trust it
 * again, and neither will they trust the parent who installed it.
 *
 * **It is honestly less permanent than the requirement implies.** An ongoing notification is
 * dismissible from Android 14 onward, and on API 33+ it does not appear at all if the child
 * declines the notification permission. Neither hole is closable without running a foreground
 * service from the container app, which is a heavy thing to add to an IME. The mitigation is
 * that the supervision screen inside the app states the same facts and cannot be dismissed at
 * all, so the notification is the reminder rather than the disclosure.
 */
object SupervisionNotice {

    private const val CHANNEL_ID = "supervision"
    private const val NOTIFICATION_ID = 1001

    /**
     * @param scope what a parent can currently see. The notice text depends on it, because the
     *   old wording — "they cannot see what you typed" — is simply false at
     *   [ReviewScope.FULL_TEXT], and a standing notice that is out of date on the one fact it
     *   exists to state is worse than no notice at all. Defaults to the strictest reading, so a
     *   caller that has not looked up the policy under-claims rather than over-claims.
     */
    fun show(context: Context, scope: ReviewScope = ReviewScope.CONCERNING_ONLY) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.supervision_notice_title))
            .setContentText(
                context.getString(
                    // The collapsed line is the one most people ever read, so full review gets
                    // its own rather than being a detail you have to expand to find.
                    if (scope == ReviewScope.FULL_TEXT) {
                        R.string.supervision_notice_text_full
                    } else {
                        R.string.supervision_notice_text
                    },
                ),
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        context.getString(
                            when (scope) {
                                ReviewScope.CONCERNING_ONLY -> R.string.supervision_notice_detail
                                ReviewScope.THEMES -> R.string.supervision_notice_detail_themes
                                ReviewScope.FULL_TEXT -> R.string.supervision_notice_detail_full
                            },
                        ),
                    ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .build()

        // Throws without the runtime permission on API 33+, where a child may simply have said
        // no. Swallowed rather than crashed: the in-app screen still carries the disclosure.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    fun hide(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Puts the notice in whichever state the supervision flag says it should be in.
     *
     * Reads the scope off the cached policy rather than taking it as a parameter, so every
     * caller gets wording that matches the policy actually in force. A sync that changes the
     * scope calls [show] directly with the new value, which is what makes the notice update in
     * the same moment the setting does rather than at the next launch.
     */
    fun refresh(context: Context, supervision: Supervision) {
        if (supervision.isSupervised) {
            show(context, supervision.policy?.reviewScope ?: ReviewScope.CONCERNING_ONLY)
        } else {
            hide(context)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.supervision_channel_name),
            // Low, not default: this is a fact that must stay visible, not an interruption.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.supervision_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }
}
