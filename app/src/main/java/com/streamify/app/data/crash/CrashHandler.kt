package com.streamify.app.data.crash

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import com.streamify.app.BuildConfig
import com.streamify.app.ui.crash.CrashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6 \u00b7 Step 6.4 \u2014 global UncaughtExceptionHandler.
 *
 * Installed once from
 * [com.streamify.app.StreamifyApp.onCreate] right after the Sentry SDK
 * initialises. The handler:
 *
 *   1. atomic re-entry guard \u2014 if a crash fires while we are still
 *      inside the previous `uncaughtException` call (e.g. the chain itself
 *      blows up), skip everything and forward to the previous default.
 *   2. Synchronously write a structured crash dump to
 *      `Context.filesDir/crash/crash-report-current.log` so the next
 *      [CrashActivity] can read it without a race.
 *   3. Launch [CrashActivity] via a NEW_TASK | CLEAR_TASK Intent so the
 *      recovery surface appears in the foreground.
 *   4. Chain to whatever `Thread.getDefaultUncaughtExceptionHandler()`
 *      returned at install time \u2014 in this project that's the Sentry
 *      SDK's chain, which captures the event synchronously and forwards
 *      to Android's KillApplicationHandler.
 *
 * IMPORTANT: we DO NOT call `Sentry.captureException` ourselves. Sentry's
 * own handler (chained at install) does that already; if we also call it,
 * a single crash would be reported twice and log noise multiplies.
 *
 * IMPORTANT: we DO NOT broadcast to `FloatingPlayerService`. The OS
 * already reaps a dying process's foreground services cleanly.
 *
 * IMPORTANT: we DO NOT chain to a process-kill manually because the
 * Sentry handler chain forwards to KillApplicationHandler; if Sentry is
 * disabled (DSN blank), the previous default will be the system's
 * KillApplicationHandler which still does the right thing.
 */
class CrashHandler private constructor(private val appContext: Context) :
    Thread.UncaughtExceptionHandler {

    private val previous: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // 1. Re-entry guard. If something inside the handler itself
        // throws (e.g. a half-corrupted crash dump write), we MUST NOT
        // loop. The second invocation just forwards to the chain.
        if (!ENTRANCE.compareAndSet(false, true)) {
            Log.w(TAG, "re-entrant crash \u2014 skipping dump + activity, chaining")
            chain(thread, throwable)
            return
        }

        // 2. Write the local dump. MUST happen before any Binder IPC
        // (startActivity) because the OS may reap us any time after that.
        try {
            writeCrashReport(thread, throwable)
        } catch (t: Throwable) {
            Log.e(TAG, "crash dump write threw", t)
        }

        // 3. Launch the recovery UI. New isolated process so a failed
        // StreamifyApp.onCreate doesn't prevent the user from restarting
        // \u2014 see AndroidManifest's `android:process=":crash"` on
        // CrashActivity.
        try {
            val intent = Intent(appContext, CrashActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(CrashActivity.EXTRA_FROM_CRASH, true)
            appContext.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "could not start CrashActivity", t)
        }

        // 4. Forward to whatever was installed before us. In the
        // StreamifyApp wiring order that is Sentry's handler \u2014
        // which calls Sentry.captureException synchronously, flushes,
        // and forwards to Android's KillApplicationHandler.
        chain(thread, throwable)
    }

    private fun chain(thread: Thread, throwable: Throwable) {
        if (previous != null && previous !== this) {
            previous.uncaughtException(thread, throwable)
            return
        }
        // No previous handler \u2014 must end the process so we don't ANR.
        Process.killProcess(Process.myPid())
        System.exit(10)
    }

    /**
     * Format choice: text-only dump (timestamp, app version, device,
     * thread, full stack trace + causal chain + suppressed exceptions).
     * Single file overwrite \u2014 last crash wins; retention would just
     * fill the device without giving the user a way to read older ones.
     */
    private fun writeCrashReport(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val timestamp = SimpleDateFormat(TS_FMT, Locale.US).format(Date())
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, SDK ${Build.VERSION.SDK_INT})"

        val report = buildString {
            appendLine("SportStream crash report")
            appendLine("Timestamp: $timestamp")
            appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: $device")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("\u2500\u2500\u2500\u2500")
            appendLine(sw.toString())
        }

        val dir = File(appContext.filesDir, "crash").apply { mkdirs() }
        val file = File(dir, FILE_NAME)
        // Atomic temp+rename. `file.writeText(report)` opens a 
        // FileOutputStream + write + close with NO `fsync` — if the 
        // dying process is reaped mid-write the on-disk file is a 
        // truncated blur. The OS-reaped CrashActivity.onCreate then 
        // reads garbage. Renames on a single filesystem are atomic on 
        // Android internal storage (per Android storage docs), so a 
        // crash mid-write leaves either the previous file intact OR 
        // the fully-written new file — never a partial-read junk file.
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(report)
        if (!tmp.renameTo(file)) {
            // Some FUSE-backed filesystems reject rename-over-existing. 
            // Drop-then-rename is the well-known fallback that retains 
            // atomicity on the second hop.
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val TAG = "SportStreamCrashHandler"
        private const val FILE_NAME = "crash-report-current.log"
        private const val TS_FMT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX"

        private val ENTRANCE = AtomicBoolean(false)

        @Volatile
        private var installed: CrashHandler? = null

        /** Install once. Idempotent + thread-safe via double-checked locking. */
        fun install(appContext: Context) {
            if (installed != null) return
            synchronized(this) {
                if (installed != null) return
                val handler = CrashHandler(appContext.applicationContext)
                Thread.setDefaultUncaughtExceptionHandler(handler)
                installed = handler
            }
        }

        /**
         * UI-thread helper used by [CrashActivity.onCreate]. Safe \u2014 just
         * reads a small text file from internal storage. Returns null
         * when no crash has been persisted yet.
         */
        fun readLatestCrashReport(context: Context): String? {
            val f = File(File(context.filesDir, "crash"), FILE_NAME)
            if (!f.exists() || f.length() == 0L) return null
            return runCatching { f.readText() }.getOrNull()
        }

        /** Test seam: clear the re-entry guard between unit-test runs. */
        @Suppress("unused")
        internal fun resetForTest() {
            ENTRANCE.set(false)
            installed = null
        }
    }
}
