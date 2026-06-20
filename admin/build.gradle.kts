// Phase 8 * Step 8.13 -- Android Admin app module.
//
//   applicationId = "com.streamify.admin"
//   orange Material3 theme (distinct from the cyan user app)
//   ViewBinding on, but NO google-services plugin (no FCM for admins),
//   NO kapt (no Room yet), NO Media3 (no player UI in admin).
//
// Reuses the SAME `libs.versions.toml` as the user app for hygienic multi-
// module dependency management. Compose is deliberately NOT used here so we
// don't need to add compose-bom + 6 compose-* aliases to libs.versions.toml
// yet -- the admin UI is straightforward and XML+ViewBinding stays consistent
// with the user app's stack.
//
// Phase 6 · Step 6.5 — `java.util.Properties` import placed AFTER the
// `plugins {}` block to avoid Gradle's `java {}` extension shadowing.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // alias(libs.plugins.sentry.android)  // Step 6.5 — deferred until admin module is actively developed
}

import java.util.Properties

android {
    namespace = "com.streamify.admin"
    compileSdk = 35

    // Step 6.5 — same hoisted properties pattern as :app.
    val rootSigningProps = Properties()
    rootProject.file("signing.properties").takeIf { it.exists() }?.let { f ->
        f.inputStream().use { stream -> rootSigningProps.load(stream) }
    }

    defaultConfig {
        applicationId = "com.streamify.admin"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Step 6.5 — separate DSN slot for the admin app. Two Sentry
        // projects so admin crashes don't pollute user-app release health.
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"" + (rootSigningProps.getProperty("ADMIN_SENTRY_DSN") ?: "") + "\""
        )
    }

    buildFeatures {
        viewBinding = true   // Same pattern as :app
        buildConfig = true   // Needed for BuildConfig.DEBUG in AdminApi (mirrors :app)
    }

    // -----------------------------------------------------------------
    // Phase 6 · Step 6.5 — Release signing. Same fallback-to-debug
    // pattern as `:app`; see `app/build.gradle.kts` for the rationale.
    //
    // Implementation note: we deliberately do NOT nest `apply {}` inside
    // `use {}` because Kotlin's receiver-resolution makes `load(it)` then
    // ambiguous (it resolves to the inner InputStream receiver, not
    // Properties). An explicit named lambda parameter + a `p.load()`
    // call sidesteps the trap.
    // -----------------------------------------------------------------
    signingConfigs {
        create("release") {
            // Step 6.5 — reuse `rootSigningProps` hoisted above (avoids
            // re-reading the file twice).
            if (rootSigningProps.isNotEmpty()) {
                // Admin may use a DIFFERENT keystore than :app — fall
                // back to the user-app keystore fields when admin-
                // specific keys are absent.
                storeFile = file(rootSigningProps.getProperty("RELEASE_ADMIN_STORE_FILE")
                    ?: rootSigningProps.getProperty("RELEASE_STORE_FILE") ?: "")
                storePassword = rootSigningProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = rootSigningProps.getProperty("RELEASE_ADMIN_KEY_ALIAS")
                    ?: rootSigningProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = rootSigningProps.getProperty("RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // -----------------------------------------------------------------
    // Step 6.5 — Sentry Gradle plugin extension. Distinct `projectName`
    // so admin crash mappings end up under a separate Sentry project
    // (admin events should NOT tangle with user-app release health).
    // -----------------------------------------------------------------
    // Sentry plugin config — deferred until admin module is actively developed.
    // sentry {
    //     org = "sportstream-app"
    //     projectName = "sportstream-admin-android"
    //     authToken = (rootSigningProps.getProperty("SENTRY_AUTH_TOKEN") ?: "").trim()
    //     autoUploadProguardMapping = true
    //     includeSourceContext = true
    //     uploadNativeSymbols = false
    // }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Same `takeIf { storeFile != null }` guard as :app — falls
            // back to debug signing when signing.properties is absent.
            signingConfig = signingConfigs.findByName("release")
                ?.takeIf { it.storeFile != null }
                ?: signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // -- Material 3 (orange theme for admin) --
    implementation(libs.material)

    // -- Sentry (same as :app -- admins crash too) --
    implementation(libs.sentry.android)

    // -- ViewModel + LiveData (login state, dashboard state) --
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)

    // -- Networking (mirrors :app's ApiClient) --
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // -- Coroutines --
    implementation(libs.kotlinx.coroutines.android)

    // -- DataStore Preferences (token persistence) --
    implementation(libs.datastore.preferences)

    // -- Gson (admin response payload mirrors :app) --
    implementation(libs.gson)

    // -- Framgement + Navigation (admin uses single-activity + fragments) --
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
}
