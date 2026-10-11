package io.github.duddmeister.lumen.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.duddmeister.lumen.BuildConfig
import io.github.duddmeister.lumen.data.Http
import io.github.duddmeister.lumen.data.HttpStatusException
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Keeps Lumen current from the GitHub releases of the repository that built it: finds
 * the newest version, downloads it and hands it to Android's package installer, which
 * asks before updating. Work runs on one background thread; listeners hear each change
 * on the main thread.
 */
object Updater {

    sealed class State {
        /** Not checked yet. */
        data object Idle : State()
        data object Checking : State()
        data object Current : State()
        class Available(val release: Release) : State()
        /** Android must first allow Lumen to install apps. */
        class NeedsPermission(val release: Release) : State()
        class Downloading(val release: Release, val progress: Float) : State()
        /** With Android's installer; [confirm] is its "update this app?" screen once it asks. */
        class Installing(val release: Release, val confirm: Intent?) : State()
        class Failed(val message: String, val release: Release?) : State()
    }

    /** The "owner/name" repository to update from; empty in builds that can't install Lumen's releases. */
    internal var repo = BuildConfig.UPDATE_REPO

    val enabled: Boolean get() = repo.isNotEmpty()

    var state: State = State.Idle
        private set

    private val listeners = ArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "lumen-update").apply { isDaemon = true } }
    private var checkedAt = 0L

    fun listen(listener: (State) -> Unit) {
        listeners.add(listener)
    }

    fun unlisten(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    internal fun set(s: State) {
        state = s
        for (l in listeners.toList()) l(s)
    }

    private val busy: Boolean
        get() = state is State.Checking || state is State.Downloading || state is State.Installing

    /** Reads an answer from GitHub's API; tests stand in for GitHub here. */
    internal var fetch: (String) -> String = { url -> String(Http.get(url, MAX_ANSWER_BYTES), Charsets.UTF_8) }

    /**
     * The newest release, or null when there's none Lumen can install. Blocks, and throws
     * [IOException] or [JSONException] when GitHub can't be read.
     */
    internal fun latest(): Release? = try {
        Releases.parse(fetch("https://api.github.com/repos/$repo/releases/latest"))
    } catch (e: HttpStatusException) {
        // 404: nothing released yet.
        if (e.code == 404) null else throw e
    }

    /** Looks for a newer release. Unless [now], skips it when the last look was recent. */
    fun check(now: Boolean) {
        if (!enabled || busy) return
        if (!now && state !is State.Idle && SystemClock.elapsedRealtime() - checkedAt < RECHECK_MS) return
        set(State.Checking)
        worker.execute {
            val next = try {
                val release = latest()
                if (release != null && release.versionCode > BuildConfig.VERSION_CODE) State.Available(release) else State.Current
            } catch (e: HttpStatusException) {
                State.Failed("GitHub couldn't answer (error ${e.code}). Try again later.", null)
            } catch (e: IOException) {
                State.Failed("Couldn't reach GitHub. Check your connection, then try again.", null)
            } catch (e: JSONException) {
                State.Failed("GitHub sent something unexpected. Try again later.", null)
            }
            main.post {
                checkedAt = SystemClock.elapsedRealtime()
                set(next)
            }
        }
    }

    /** Downloads [release] and hands it to Android, which asks before installing it. */
    fun install(context: Context, release: Release) {
        if (busy) return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            set(State.NeedsPermission(release))
            return
        }
        set(State.Downloading(release, 0f))
        worker.execute {
            try {
                val apk = download(app, release) { progress ->
                    main.post { if (state is State.Downloading) set(State.Downloading(release, progress)) }
                }
                if (!isNewerLumen(app, apk)) {
                    main.post { set(State.Failed("That download isn't a newer Lumen, so it wasn't installed.", release)) }
                    return@execute
                }
                main.post { set(State.Installing(release, null)) }
                startInstall(app, apk)
            } catch (e: IOException) {
                main.post { set(State.Failed("Couldn't download the update. Check your connection, then try again.", release)) }
            } catch (e: SecurityException) {
                main.post { set(State.Failed("Android didn't let Lumen install the update.", release)) }
            } catch (e: RuntimeException) {
                // Never let a failed update take the keyboard down with it.
                main.post { set(State.Failed("The update didn't install. Try again later.", release)) }
            }
        }
    }

    /** Called by [UpdateReceiver] as Android's installer reports back. */
    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        val release = (state as? State.Installing)?.release
        when {
            status == PackageInstaller.STATUS_PENDING_USER_ACTION && release != null -> set(State.Installing(release, confirm))
            status == PackageInstaller.STATUS_SUCCESS -> set(State.Current)
            // Cancelled at Android's prompt: offer it again.
            status == PackageInstaller.STATUS_FAILURE_ABORTED && release != null -> set(State.Available(release))
            status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> set(
                State.Failed("This update is signed differently from the Lumen you have. Install it once from GitHub instead.", release),
            )
            status != PackageInstaller.STATUS_PENDING_USER_ACTION -> set(
                State.Failed("The update didn't install" + (message?.let { ": $it" } ?: "."), release),
            )
        }
    }

    /** The activity showed Android's prompt; don't show it again. */
    fun confirmShown() {
        val s = state as? State.Installing ?: return
        set(State.Installing(s.release, null))
    }

    private fun download(context: Context, release: Release, progress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "lumen-${release.versionCode}.apk")
        val c = URL(release.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("User-Agent", "Lumen/" + BuildConfig.VERSION_NAME)
        try {
            if (c.responseCode !in 200..299) throw HttpStatusException(c.responseCode)
            val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done > MAX_APK_BYTES) throw IOException("Update too large")
                        val now = SystemClock.uptimeMillis()
                        if (total > 0 && now - reported >= PROGRESS_MS) {
                            reported = now
                            progress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            if (release.apkSize > 0 && file.length() != release.apkSize) throw IOException("Incomplete download")
            return file
        } finally {
            c.disconnect()
        }
    }

    /** Only ever hand Android a newer version of this very app. */
    private fun isNewerLumen(context: Context, apk: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.path, 0) ?: return false
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        return info.packageName == context.packageName && version > BuildConfig.VERSION_CODE
    }

    /** Writes [apk] into an install session; [UpdateReceiver] hears how it goes. */
    internal fun startInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("lumen.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutable so the installer can add its status; explicit, so only Lumen hears it.
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(callback.intentSender)
        }
    }

    private const val RECHECK_MS = 30 * 60_000L
    private const val MAX_ANSWER_BYTES = 1 shl 20
    private const val MAX_APK_BYTES = 64L shl 20
    private const val PROGRESS_MS = 100L
}
