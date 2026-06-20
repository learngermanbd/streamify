package com.streamify.admin

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class StreamifyAdminApp : Application() {

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

    override fun onCreate() {
        super.onCreate()
        SentryAndroid.init(this) { options ->
            options.dsn = BuildConfig.SENTRY_DSN.takeIf { it.isNotBlank() }
            options.isDebug = BuildConfig.DEBUG
            options.sampleRate = 1.0
            options.tracesSampleRate = 0.0
            options.isEnableAutoSessionTracking = !BuildConfig.DEBUG
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
            options.isAttachStacktrace = true
            options.isAttachThreads = true
            options.isAttachViewHierarchy = false
            options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrubPii(event) }
            options.setTag("versionName", BuildConfig.VERSION_NAME)
            options.setTag("versionCode", BuildConfig.VERSION_CODE.toString())
            options.setTag("process", "admin-main")
        }
    }

    private fun scrubPii(event: SentryEvent): SentryEvent? {
        val piiPatterns = listOf(
            Regex("(?i)(Bearer\\s+)[A-Za-z0-9\\-._~+/]+=*"),
            Regex("(?i)(FCM[_-]?Token[:= ]?)[A-Za-z0-9_\\-]{140,}"),
            Regex("(?i)(android[_-]?id[:= ]?)[A-Fa-f0-9\\-]{8,}"),
            Regex("(?i)(Cookie[:= ]?)[^\\s;]+"),
            Regex("(?i)(Authorization[:= ]?)[A-Za-z0-9\\-._~+/]+=*"),
        )
        fun redact(input: String?): String? {
            if (input.isNullOrBlank()) return input
            var scrubbed: String = input
            piiPatterns.forEach { scrubbed = scrubbed.replace(it, "$1[Filtered]") }
            return scrubbed
        }
        event.message?.message?.let { event.message?.message = redact(it) }
        event.breadcrumbs?.forEach { bc ->
            bc.message?.let { bc.message = redact(it) }
            bc.data?.keys?.toList()?.forEach { k ->
                bc.data?.get(k)?.let { v ->
                    bc.data?.put(k, redact((v ?: "").toString()))
                }
            }
        }
        event.request?.headers?.let { headers ->
            headers.keys.toList().forEach { k ->
                if (k.equals("Authorization", true) ||
                    k.contains("Token", true) ||
                    k.contains("Cookie", true)
                ) {
                    headers[k] = "[Filtered]"
                }
            }
        }
        return event
    }

    companion object {
        const val ADMIN_API_BASE_URL = "https://learngermanwith.fun"

        val Context.adminTokenStore by preferencesDataStore(
            name = "streamify_admin_tokens"
        )
    }
}
