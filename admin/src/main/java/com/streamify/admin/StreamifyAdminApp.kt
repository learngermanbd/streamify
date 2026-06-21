package com.streamify.admin

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class StreamifyAdminApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Default uncaught-exception handler — log every uncaught Throwable
        // with a stable tag so a future crash is easy to capture from logcat:
        //   adb logcat -s StreamifyAdminApp.Crash AndroidRuntime:E *:S
        // We still delegate to the previously-installed handler so the OS /
        // Crashlytics still receive the exception (crash dialog, ANR, etc.).
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** In-memory JWT token. Set after successful login. */
    var adminToken: String = ""
    var adminRefreshToken: String = ""
    var adminName: String = "Admin"
    var adminRole: String = "EDITOR"

    companion object {
        const val ADMIN_API_BASE_URL = "https://learngermanwith.fun"

        /** Stable logcat tag used by [StreamifyAdminApp]'s default uncaught-exception handler. */
        const val TAG = "StreamifyAdminApp.Crash"

        val Context.adminTokenStore by preferencesDataStore(
            name = "streamify_admin_tokens"
        )
    }
}
