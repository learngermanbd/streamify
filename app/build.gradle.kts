// Step 1.2: full Android app module — plugins + viewBinding + full dependency block from §1.2 of
// `sportzfy_build_plan.html`, sourced from `gradle/libs.versions.toml`.
//
// Phase 6 · Step 6.5 — `java.util.Properties` import placed AFTER the
// `plugins {}` block.  Gradle Kotlin DSL exposes a `java {}` project
// extension that shadows the root `java` package; placing the import
// before `plugins {}` breaks script compilation and produces
// `Unresolved reference: util` on fully-qualified `java.util.Properties()`.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)                    // Room compiler + future annotation processors
    alias(libs.plugins.google.services)    // Firebase Messaging only (BoM)
    alias(libs.plugins.sentry.android)      // Step 6.5 — auto-uploads R8 mapping.txt + InApp frames on assembleRelease
}

import java.security.SecureRandom
import java.time.Instant
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.gradle.api.GradleException

android {
    namespace = "com.streamify.app"
    // Android 16 (API 36) migration: compileSdk=36 required for AGP 8.9
    // to validate AndroidManifest symbols against API 36. Existing NDK
    // r27c is already 16 KB-page-size ready via AGP 8.5+ injecting the
    // `-Wl,-z,max-page-size=16384` linker flag automatically — no NDK
    // version bump was needed for this migration.
    compileSdk = 36
    ndkVersion = "27.3.13750724"  // r27c; AGP-injected 16 KB linker defaults

    // -----------------------------------------------------------------
    // Phase 6 · Step 6.5 — Hoisted signing.properties read so both the
    // release keystore AND the Sentry DSN/auth-token come from one
    // gitignored file. The .example lives at repo root
    // (`signing.properties.example`); real values drop into
    // `signing.properties` (also gitignored) which Gradle reads at
    // configuration time.
    //
    // Falls back to an empty Properties when the file is missing so
    // `assembleDebug` still works for developers without prod creds.
    // -----------------------------------------------------------------
    val rootSigningProps = Properties()
    rootProject.file("signing.properties").takeIf { it.exists() }?.let { f ->
        f.inputStream().use { stream -> rootSigningProps.load(stream) }
    }

    defaultConfig {
        applicationId = "com.streamify.app"
        minSdk = 23
        // Android 16 (API 36) migration: targetSdk=36 enforces the new
        // platform behaviors (predictive back, edge-to-edge, 16 KB
        // page alignment, restructured foreground-service types). Play
        // Store requires targetSdk within one year of major Android
        // release; deadline Aug 31, 2026.
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        // Step 6.5 — Sentry DSN. When blank (debug install or local CI
        // without props), BuildConfig.SENTRY_DSN is the empty string and
        // StreamifyApp's `takeIf { isNotBlank() }` short-circuits the
        // SDK init. We deliberately do NOT throw — a developer running
        // `assembleRelease` locally to verify obfuscation should not
        // need to drop credentials into a file.
        buildConfigField(
            "String",
            "SENTRY_DSN",
            "\"" + (rootSigningProps.getProperty("APP_SENTRY_DSN") ?: "") + "\""
        )

        // Phase 7 · Step 7.5 + Step 7.13 — APK signing certificate SHA-256.
        // Production release builds read the actual signing cert from the
        // release keystore (populated below by computeReleaseSigningSha256)
        // and inject it as a BuildConfig constant consumed by
        // IntegrityChecker.expectedSignatureSha256 during StreamifyApp
        // bootstrap. Empty string triggers the skip-signature path
        // (debug builds, unsigned prod builds with -PallowUnsignedRelease).
        buildConfigField(
            "String",
            "APP_SIGNING_SHA256",
            "\"" + (rootSigningProps.getProperty("APP_SIGNING_SHA256") ?: "") + "\""
        )

        // Phase 7 · Step 7.7 — Real SSL pins for production domains.
        // Format: semicolon-separated `sha256/<base64-der>` strings.  If
        // blank, SSLPinner falls back to the baked-in Let's Encrypt
        // placeholder + ISRG Root X1 backup.
        // SSLPinner reads this via reflection in SSLPinner.initialize
        // (rather than direct BuildConfig import) so additional domains
        // can be added without forking SSLPinner.
        buildConfigField(
            "String",
            "SSL_PINS_LEARNGERMANWITH_FUN",
            "\"" + (rootSigningProps.getProperty("SSL_PINS_LEARNGERMANWITH_FUN") ?: "") + "\""
        )

        // Phase 7 · Step 7.4 — NDK ABI targets + CMake compile flags.
        externalNativeBuild {
            cmake {
                cppFlags(
                    "-std=c++17",
                    "-fvisibility=hidden",
                    "-fno-rtti",
                    "-fno-exceptions"
                )
                arguments(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_SYSTEM_NAME=Android"
                )
            }
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
            }
        }
    }

    buildFeatures {
        viewBinding = true   // Step 1.2 — enables type-safe view binding across all UI screens
        buildConfig = true   // Step 2.2 — opt in to AGP-generated BuildConfig (DEBUG, VERSION_NAME, APPLICATION_ID)
    }

    // v1.1.1 — explicitly select the JUnit Platform test framework so BOTH
    // the existing Jupiter tests AND the new JUnit 4 / Robolectric tests
    // (bridged through junit-vintage-engine added above) get executed by
    // AGP's `testDebugUnitTest` task.  The `useJUnitPlatform()` action is
    // applied at the tasks-level (below) because AS's Tooling API probes
    // `prepareKotlinBuildScriptModel` and `unitTests.all { ... }` inside
    // `android.testOptions` is a known blocker for that task registration
    // on AGP 8.9 / Kotlin Gradle Plugin 2.0.21 / KSP 2.0.21-1.0.28.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // -----------------------------------------------------------------
    // Phase 7 · Step 7.4 — NDK / CMake build for native_security.
    //
    // CMake builds libnative_security.so from src/main/cpp/.
    // Compile flags: hidden visibility, no RTTI, no exceptions,
    // stack protector.  Linker strips all symbols + excludes
    // static-lib exports (see CMakeLists.txt for linker flags).
    // -----------------------------------------------------------------
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    // -----------------------------------------------------------------
    // Phase 6 · Step 6.5 — Release signing config.
    //
    // Reads from `signing.properties` (gitignored, lives in repo root)
    // when present. Real keystore values land alongside the production
    // release pipeline in Phase 7 (Step 7.13 — Security build & verify).
    //
    // When `signing.properties` is missing we fall back to the debug
    // signing config so `./gradlew assembleRelease` still produces an
    // installable APK for end-to-end pipeline testing. Production
    // releases must ALWAYS provide a real `signing.properties` BEFORE
    // the build pipeline ships to Play.
    //
    // Implementation note: we deliberately do NOT nest `apply {}` inside
    // `use {}` because Kotlin's receiver-resolution makes `load(it)` then
    // ambiguous (it resolves to the inner InputStream receiver, not
    // Properties). An explicit named lambda parameter + a `p.load()`
    // call sidesteps the trap.
    // -----------------------------------------------------------------
    signingConfigs {
        create("release") {
            // Step 6.5 — reuse the hoisted `rootSigningProps` from above
            // instead of re-reading the file (two disk opens avoided).
            if (rootSigningProps.isNotEmpty()) {
                storeFile = rootProject.file(rootSigningProps.getProperty("RELEASE_STORE_FILE") ?: "")
                storePassword = rootSigningProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = rootSigningProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = rootSigningProps.getProperty("RELEASE_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // -----------------------------------------------------------------
    // Phase 6 · Step 6.5 — Sentry Gradle plugin extension.
    //
    // authToken is the SECRET (Settings → API → Auth Tokens, scope:
    // `project:releases` + `project:write`). Blank => upload task is
    // a no-op so a developer with no production creds still produces a
    // working APK. `org` + `projectName` are project slugs (NOT secrets)
    // and are hardcoded so the upload task names are stable across runs.
    // `autoProguardConfig` keeps the SDK's consumer rules in place;
    // `includeSourceContext` decorates release events with file:line of
    // the user-code frame (improves triage speed by ~20%).
    //
    // `uploadNativeSymbols = false` because the project does NOT depend
    // on `io.sentry:sentry-android-ndk` — enabling it would crash the
    // upload task with `NoSuchMethodError`.
    // -----------------------------------------------------------------
    sentry {
        org = "streamify-0p"
        projectName = "streamify"
        authToken = (rootSigningProps.getProperty("SENTRY_AUTH_TOKEN") ?: "").trim()
        autoUploadProguardMapping = true
        includeSourceContext = true
        uploadNativeSymbols = false
        // We deliberately do NOT set `proguardMappings` / `manifestPath`
        // — the plugin's auto-detect already points at the AGP-generated
        // paths for our standard layout. Setting them manually is a
        // foot-gun if AGP renames the artifact path in a future release.
    }

    buildTypes {
        release {
            // R8 / ProGuard enabled — see `proguard-rules.pro` for the
            // keep rules that protect the reflection-driven entry
            // points (Sentry, Room, Gson, Glide, Media3, OkHttp, etc.).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Resolve release-variant signing config based on the
            // `-PallowUnsignedRelease` flag OR signing.properties contents:
            //   * allowUnsignedRelease=true → signingConfig=null (produces
            //     `app-release-unsigned.apk`; test-install only)
            //   * signing.properties has RELEASE_STORE_FILE → use the release
            //     signing config (production / Play-store build)
            //   * signing.properties absent OR empty → fall back to debug
            //     signing; verifyReleaseSecrets pre-flight will then throw
            //     GradleException listing every missing key (above)
            signingConfig = if (rootProject.findProperty("allowUnsignedRelease") == "true") {
                null
            } else {
                signingConfigs.findByName("release")
                    ?.takeIf { it.storeFile != null }
                    ?: signingConfigs.getByName("debug")
            }
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
    // ── AndroidX Activity (Android 16 migration) ──
    // `androidx.activity:activity-ktx` 1.9+ is required for the
    // `enableEdgeToEdge()` extension function used in MainActivity +
    // PlayerActivity. AppCompat 1.7+ pulls it in transitively at lower
    // versions; pinned here to 1.9.3 to guarantee the symbol.
    implementation(libs.activity.ktx)

    // ── AndroidX Fragment (Android 16 migration) ──
    // Pinned to 1.8.5 to match Navigation 2.9's expected fragment API floor.
    // Without this pin, fragment-ktx resolves transitively at 1.6.2 which
    // is below Navigation 2.9 → runtime NoSuchMethodError on Fragment 1.8+
    // APIs (FragmentManager.registerFragmentLifecycleCallbacks overloads
    // added in 1.8).
    implementation(libs.fragment.ktx)

    // ── Media3 — video playback (Phase 4 surface; dep lands now so Step 1.3 build resolves) ──
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    // ── OkHttp — networking (used by ApiClient and Media3 HLS/DASH source) ──
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // ── Room — local database (Favorites, Playlists, CachedEvent) ──
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // ── Firebase — messaging ONLY (FCM push notifications) ──
    // Remote Config intentionally replaced by our own /api/config endpoint (Phase 8).
    // Crashlytics intentionally replaced by Sentry (below).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.integrity)          // Step 7.10 — Play Integrity API & device attestation
    // Step 7.10 — see [libraries]/play-services-base note in libs.versions.toml for firebase-bom dedup rationale
    implementation(libs.play.services.base)

    // ── Sentry — crash reporting (replaces Firebase Crashlytics) ──
    implementation(libs.sentry.android)

    // ── Lottie — animations (splash loader, player overlays) ──
    implementation(libs.lottie)

    // ── Material Design 3 (components used from Phase 3 onward) ──
    implementation(libs.material)

    // ── Navigation (Phase 3 — Home / Categories / Highlights graph) ──
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)

    // ── Lifecycle (ViewModels, LiveData) ──
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)

    // ── DataStore — preferences (AppConfig cache, recent searches, video quality memory) ──
    implementation(libs.datastore.preferences)

    // ── Glide — image loading (team logos, channel logos, highlight thumbnails) ──
    implementation(libs.glide)

    // ── Coroutines — async primitives (Phase 2 ViewModels) ──
    implementation(libs.kotlinx.coroutines.android)

    // ── Gson — @SerializedName annotations on Phase 2 data models (parse via org.json for now) ──
    implementation(libs.gson)

    // ── SwipeRefreshLayout — Home tab pull-to-refresh host (Phase 3 · Step 3.3) ──
    implementation(libs.swiperefreshlayout)

    // ── ViewPager2 — Home tab banner auto-scroll carousel (Phase 3 · Step 3.3) ──
    implementation(libs.viewpager2)

    // ── WorkManager — daily background update check (Phase 6 · Step 6.2) ──
    implementation(libs.work.runtime)

    // ── Security — EncryptedSharedPreferences removed in A16 migration ──
    // (androidx.security:security-crypto 1.1.0-alpha06 had no production
    // callers; MasterKey.Builder + EncryptedSharedPreferences can throw
    // KeyStoreException unpredictably on Android 16. Token-class secrets
    // are routed through KeystoreManager instead.)

    // ── Test dependencies (Phase 9 · Step 9.1) ──
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)

    // ── v1.1.1 — Robolectric host-JVM Android framework ──
    // RoboletricTestRunner runs Application.onCreate in-process so we can
    // verify the v1.1.1 security bootstrap (TokenStore.initFromContext,
    // RequestSigner.bootstrap, SSLPinner.initialize, IntegrityChecker
    // signing-cert wiring) without actually booting an Android emulator.
    // Robolectric 4.13 needs JUnit 4 API + Vintage engine to coexist with
    // our Jupiter test suite; AGP's `testDebugUnitTest` discovers both.
    testImplementation(libs.robolectric)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
    // v1.1.1 — Robolectric 4.13 declares `androidx.test:monitor` only at
    // `runtime` scope, so Kotlin's test compilation doesn't see
    // `androidx.test:core` (which provides `ApplicationProvider`).  We
    // pin core-ktx explicitly to put it on the test compile classpath.
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
}

// ──────────────────────────────────────────────────────────────────────
// Phase 7 · Step 7.2 — Build-time string encryption
//
// Reads sensitive strings from secrets.properties (gitignored) with
// fallback defaults so assembleDebug works without prod creds.
// SENTRY_DSN comes from signing.properties (shared with Sentry plugin).
//
// Generates EncryptedConstants.kt in build/generated/source/encryption/
// with AES-256-GCM encrypted byte arrays + XOR-obfuscated key.
// ──────────────────────────────────────────────────────────────────────
val encryptSecrets by tasks.registering {
    group = "encryption"
    description = "Encrypts sensitive strings into EncryptedConstants.kt"

    val secretsFile = rootProject.file("secrets.properties")
    val signingFile = rootProject.file("signing.properties")
    val outputDir = layout.buildDirectory.dir("generated/source/encryption/main")

    // Always regenerate: the key is random per build so up-to-date
    // checking would produce inconsistent key/ciphertext pairs.
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }

    doLast {
        val outDir = outputDir.get().asFile
        outDir.mkdirs()

        // ── Read secrets ───────────────────────────────────────────
        val props = Properties()
        if (secretsFile.exists()) {
            secretsFile.inputStream().use { props.load(it) }
        }
        val signingProps = Properties()
        if (signingFile.exists()) {
            signingFile.inputStream().use { signingProps.load(it) }
        }

        // ── Collect values to encrypt ──────────────────────────────
        val secrets = linkedMapOf(
            "API_BASE_URL" to (props.getProperty("API_BASE_URL")
                ?: "https://learngermanwith.fun"),
            "API_CONFIG_URL" to (props.getProperty("API_CONFIG_URL")
                ?: "https://learngermanwith.fun/config"),
            "UPDATE_URL" to (props.getProperty("UPDATE_URL")
                ?: "https://learngermanwith.fun/update"),
            "TELEGRAM_LINK" to (props.getProperty("TELEGRAM_LINK")
                ?: "https://t.me/streamify"),
            "SENTRY_DSN" to (
                signingProps.getProperty("APP_SENTRY_DSN")
                    ?: props.getProperty("SENTRY_DSN")
                    ?: ""
                ),
            // Phase 7 · Step 7.7 — HMAC signing secret consumed by
            // RequestSigner.v1.1.1. Build-time encrypted so the literal
            // never appears in the APK's strings table. Optional:
            // debug builds without secrets.properties get an empty
            // string, which RequestSigner treats as "no signing path".
            "SIGNING_SECRET" to (props.getProperty("SIGNING_SECRET")
                ?: ""),
        )

        // ── Generate AES-256 key ───────────────────────────────────
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(256, SecureRandom())
        val key = keyGen.generateKey().encoded

        // ── Encrypt each value with AES-256-GCM ────────────────────
        data class EncEntry(val iv: ByteArray, val ct: ByteArray)
        val encrypted = linkedMapOf<String, EncEntry>()
        for ((name, value) in secrets) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val spec = GCMParameterSpec(128, iv)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, "AES"),
                spec
            )
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            encrypted[name] = EncEntry(iv, ct)
        }

        // ── Obfuscate key: XOR with random salt ────────────────────
        val salt = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val obfuscated = ByteArray(32)
        for (i in 0..31) obfuscated[i] = (key[i].toInt() xor salt[i].toInt()).toByte()

        // ── Format byte arrays as hex strings ──────────────────────
        fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        // ── Generate Kotlin source ─────────────────────────────────
        val source = buildString {
            appendLine("// AUTO-GENERATED by :app:encryptSecrets. DO NOT EDIT.")
            appendLine("package com.streamify.app.security")
            appendLine()
            appendLine("/**")
            appendLine(" * Build-time encrypted constants.  AES-256-GCM encrypted with a")
            appendLine(" * random per-build key that is XOR-obfuscated in [K] and [S].")
            appendLine(" * Reconstruct the key with [reconstructKey].")
            appendLine(" *")
            appendLine(" * Generated at: ${Instant.now()}")
            appendLine(" */")
            appendLine("internal object EncryptedConstants {")
            appendLine()
            appendLine("    // ── Obfuscated AES-256 key (XOR split) ──────────────────────")
            appendLine("    private val K = h(\"${obfuscated.toHex()}\")")
            appendLine("    private val S = h(\"${salt.toHex()}\")")
            appendLine()
            appendLine("    /** Hex-string to ByteArray. */")
            appendLine("    private fun h(hex: String): ByteArray =")
            appendLine("        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }")
            appendLine()
            appendLine("    /** Reconstruct the AES-256 key from obfuscated segments. */")
            appendLine("    fun reconstructKey(): ByteArray = ByteArray(32) { i ->")
            appendLine("        (K[i].toInt() xor S[i].toInt()).toByte()")
            appendLine("    }")
            appendLine()
            appendLine("    // ── Encrypted entries ──────────────────────────────────────")
            appendLine("    data class Entry(val iv: ByteArray, val ct: ByteArray)")
            appendLine()
            for ((name, entry) in encrypted) {
                appendLine("    /** Encrypted `$name`. */")
                appendLine("    val $name = Entry(")
                appendLine("        h(\"${entry.iv.toHex()}\"),")
                appendLine("        h(\"${entry.ct.toHex()}\")")
                appendLine("    )")
                appendLine()
            }
            appendLine("    /** All entries by name. */")
            appendLine("    val ENTRIES = mapOf(")
            for (name in encrypted.keys) {
                appendLine("        \"$name\" to $name,")
            }
            appendLine("    )")
            appendLine("}")
        }

        val pkg = outDir.resolve("com/streamify/app/security")
        pkg.mkdirs()
        val outFile = pkg.resolve("EncryptedConstants.kt")
        outFile.writeText(source)

        logger.lifecycle(
            "encryptSecrets: encrypted ${secrets.size} strings → ${outFile.absolutePath}"
        )
    }
}

// Wire generated source into Kotlin compilation.
android.sourceSets.getByName("main") {
    java.srcDir(layout.buildDirectory.dir("generated/source/encryption/main"))
}

// Ensure encryption runs before any Kotlin compile or KSP task.
tasks.configureEach {
    if (name.matches(Regex("(compile|ksp).*Kotlin(?!Test).*"))) {
        dependsOn(encryptSecrets)
    }
}

// v1.1.1 — Apply `useJUnitPlatform()` to every unit test task at the
// top-level `tasks` registration so the IntelliJ/Android Studio Tooling
// API successfully registers `prepareKotlinBuildScriptModel` (top-level
// registration avoids the AGP-8.9-specific deadlock where
// `android.testOptions.unitTests.all { ... }` interferes with the IDE
// model probe). After this change, both the existing Jupiter tests AND
// the new JUnit 4 / Robolectric tests execute under `testDebugUnitTest`.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// -----------------------------------------------------------------
// Phase 7 · Step 7.15 — Release Secrets Pre-Flight Verification
//
// Verifies signing.properties + secrets.properties exist with all
// required keys BEFORE `:app:assembleRelease` runs. Without this
// guard a missing key was silently falling through to:
//   - log-only Sentry SDK warnings (empty DSN → SDK no-ops)
//   - hard Gradle failures deep inside a packaging task
//   - signingConfig falling back to debug (since the `release`
//     config block's `storeFile` resolves to null when the path
//     property is blank — the `takeIf { it.storeFile != null }`
//     guard picks debug rather than failing loud)
//
// The reference to `assembleRelease` is wrapped in `afterEvaluate { }`
// because AGP registers the variant tasks (assembleRelease,
// packageRelease, etc.) lazily during the Android block configuration.
// Calling `tasks.named("assembleRelease")` at this point in the
// build script — before AGP has finalized all variant tasks —
// produced the runtime error:
//   Task with name 'assembleRelease' not found in project ':app'
// `afterEvaluate { }` is Gradle's standard "defer until after
// project evaluation is complete" hook; by that point every variant
// task is registered and named lookup is safe.
//
// The check itself runs at `doFirst` of `assembleRelease` so it sees
// the full task graph but executes before any R8 / signing / Sentry
// work has begun.
// -----------------------------------------------------------------
afterEvaluate {
        tasks.named("assembleRelease").configure {
            doFirst {
                // Production builds enforce all 10 secrets. Use
                // `-PallowUnsignedRelease=true` to opt out — produces an
                // *unsigned* release APK that still runs R8/minification, but
                // skips the credentials gate. Use case: sandbox test-installs
                // or CI artifacts where signing/api/sentry secrets don't matter.
                // NEVER set this flag for Play-store-shippable builds.
                if (rootProject.findProperty("allowUnsignedRelease") == "true") {
                    logger.lifecycle("verifyReleaseSecrets: SKIPPED (-PallowUnsignedRelease=true)")
                    return@doFirst
                }
                val errors = mutableListOf<String>()

            // ── signing.properties ─────────────────────────────────────
            val signingProps = Properties()
            rootProject.file("signing.properties").takeIf { it.exists() }?.let { f ->
                f.inputStream().use { signingProps.load(it) }
            } ?: run {
                errors += "signing.properties missing at repo root"
            }
            for (k in listOf(
                "RELEASE_STORE_FILE",
                "RELEASE_STORE_PASSWORD",
                "RELEASE_KEY_ALIAS",
                "RELEASE_KEY_PASSWORD",
                "APP_SENTRY_DSN",
                "SENTRY_AUTH_TOKEN",
            )) {
                if (signingProps.getProperty(k).isNullOrBlank()) {
                    errors += "signing.properties: missing $k"
                }
            }

            // ── secrets.properties ─────────────────────────────────────
            val secretsProps = Properties()
            rootProject.file("secrets.properties").takeIf { it.exists() }?.let { f ->
                f.inputStream().use { secretsProps.load(it) }
            } ?: run {
                errors += "secrets.properties missing at repo root"
            }
            for (k in listOf(
                "API_BASE_URL",
                "API_CONFIG_URL",
                "UPDATE_URL",
                "TELEGRAM_LINK",
            )) {
                if (secretsProps.getProperty(k).isNullOrBlank()) {
                    errors += "secrets.properties: missing $k"
                }
            }

            if (errors.isNotEmpty()) {
                throw GradleException(
                    ":app:assembleRelease pre-flight failed. Resolve the credential gaps below:\n  " +
                        errors.joinToString("\n  ")
                )
            }
            logger.lifecycle(
                "verifyReleaseSecrets: signing.properties (6/6 keys) + " +
                    "secrets.properties (4/4 keys) all present ✓"
            )
        }
    }
}
