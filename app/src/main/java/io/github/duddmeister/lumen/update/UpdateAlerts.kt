package io.github.duddmeister.lumen.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.duddmeister.lumen.BuildConfig
import io.github.duddmeister.lumen.R
import io.github.duddmeister.lumen.settings.Prefs
import io.github.duddmeister.lumen.settings.SettingsActivity
import org.json.JSONException
import java.io.IOException

/**
 * Lets the user know a new Lumen is out without them opening the app: a daily check
 * in the background while automatic checks are on, and one notification per version.
 */
object UpdateAlerts {
    private const val JOB_ID = 7001
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 1
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val FLEX_MS = 6 * 60 * 60_000L

    /** On the intent that opens the app: start the update as soon as it's found. */
    const val EXTRA_INSTALL = "io.github.duddmeister.lumen.extra.INSTALL_UPDATE"

    /** Keeps the daily check scheduled while automatic checks are on, and cancelled while they're off. */
    fun sync(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!Updater.enabled || !Prefs(context).autoUpdates) {
            scheduler.cancel(JOB_ID)
            return
        }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(DAY_MS, FLEX_MS)
                .setPersisted(true)
                .build(),
        )
    }

    /** Looks for a newer release and announces it, once per version. Blocks: not for the main thread. */
    fun checkNow(context: Context) {
        val prefs = Prefs(context)
        if (!Updater.enabled || !prefs.autoUpdates) return
        val release = try {
            Updater.latest()
        } catch (e: IOException) {
            null
        } catch (e: JSONException) {
            null
        } ?: return
        if (release.versionCode <= BuildConfig.VERSION_CODE || release.versionCode == prefs.announcedVersion) return
        announce(context, release)
        prefs.set(Prefs.ANNOUNCED_VERSION, release.versionCode)
    }

    private fun announce(context: Context, release: Release) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.updates_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, SettingsActivity::class.java)
                .putExtra(EXTRA_INSTALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val tap = context.getString(R.string.update_notification_tap)
        val news = release.changes.joinToString("\n") { "• $it" }
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_available, release.versionName))
            .setContentText(release.changes.firstOrNull() ?: tap)
            .setStyle(Notification.BigTextStyle().bigText(if (news.isEmpty()) tap else "$news\n$tap"))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications aren't allowed; the app still offers the update when opened.
        }
    }

    /** Clears the notice once the version it announced, or a newer one, is installed. */
    fun dismissIfInstalled(context: Context) {
        if (Prefs(context).announcedVersion in 1..BuildConfig.VERSION_CODE) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}

/** Runs [UpdateAlerts.checkNow] off the main thread whenever JobScheduler calls for it. */
class UpdateCheckJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        Thread({
            try {
                UpdateAlerts.checkNow(app)
            } catch (e: RuntimeException) {
                // A check that goes wrong waits for the next one rather than taking the app down.
            } finally {
                jobFinished(params, false)
            }
        }, "lumen-update-check").start()
        return true
    }

    override fun onStopJob(params: JobParameters) = false
}
