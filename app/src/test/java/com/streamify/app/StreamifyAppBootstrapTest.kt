package com.streamify.app

import androidx.test.core.app.ApplicationProvider
import com.streamify.app.security.TokenStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke test: verifies the v1.1.1 security hardening wired into
 * [StreamifyApp.onCreate] fires WITHOUT throwing the app into a crash
 * loop on a JVM-resident Android framework.
 *
 * What this test catches
 * ----------------------
 *  - TokenStore.initFromContext failing on missing KeyStore (Step 7.8).
 *  - SSLPinner.initialize failing on missing BuildConfig fields (Step 7.7).
 *  - SecurityGate.runChecks propagating an uncaught exception.
 *  - DataStore read deadlocking on Robolectric (themePrefs.apply).
 *  - CrashHandler.install writing outside the Robolectric sandbox.
 *
 * What this test does NOT catch
 * ----------------------------
 *  - Real Android-side runtime errors (only Robolectric framework shadows).
 *  - R8-stripped class-lookup issues (debug variant, R8 disabled).
 *  - Native crashes inside `libnative_security.so` (NDK not loaded by JVM).
 *  - Actual SSL pinning rejection — only the BuildConfig reflection probe
 *    runs in-process; the CertPathValidator chain check needs a real socket.
 *
 * Run via: `./gradlew :app:testDebugUnitTest --tests "*StreamifyAppBootstrapTest*"`.
 * Cached after first run: ~80 MB Robolectric `android-all-instrumented`
 * jar lands in `~/.gradle/caches/.../robolectric/` and the test runs in
 * ~10 sec.  Cold first-run download: ~60-90 sec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    application = StreamifyApp::class,
    // Robolectric 4.13 fully supports SDK levels up to 34; SDK 33 is the
    // safest floor that still exposes the APIs DataStore 1.1.x /
    // 1.1.x / WorkManager 2.10 / OkHttp 4.12 need. SDK 36 would force a
    // missing-shadow path we don't want to debug mid-test.
    sdk = [33],
)
class StreamifyAppBootstrapTest {

    /**
     * StreamifyApp.onCreate must complete without throwing.  Robolectric
     * lazy-instantiates the [Application] subclass when the first
     * Android-context-resolving call (e.g. [ApplicationProvider]) hits.
     */
    @Test
    fun `v1_1_1 hardening - StreamifyApp onCreate completes without throwing`() {
        val app = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNotNull("StreamifyApp must be instantiated by Robolectric", app)
        assertTrue(
            "Application context must be the concrete StreamifyApp subtype",
            app is StreamifyApp
        )
    }

    /**
     * TokenStore.initFromContext is the FIRST security subsystem wired
     * into [StreamifyApp.onCreate].  It must complete WITHOUT throwing,
     * even though Robolectric provides a stub KeyStore — a regression
     * here would surface in production as an unauthenticated request
     * hell (NetworkInterceptor reads the token store before OkHttp
     * bootstrap).  Letting the original exception propagate (no
     * runCatching wrapper) preserves the full stack trace for triage.
     */
    @Test
    fun `v1_1_1 hardening - TokenStore initFromContext completes`() {
        // Trigger the application construction as a side-effect of the
        // initial Android-context resolution so any onCreate crash
        // surfaces here, not deep inside TokenStore.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        TokenStore.initFromContext(ctx)
    }
}
