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

android {
    namespace = "com.streamify.app"
    compileSdk = 35
    ndkVersion = "27.3.13750724"  // Phase 7 · Step 7.4 — NDK r27c for native_security

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
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

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
                storeFile = file(rootSigningProps.getProperty("RELEASE_STORE_FILE") ?: "")
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
        org = "sportstream-app"
        projectName = "sportstream-android"
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
            // Fall back to the debug signing config when signing.properties
            // is absent. We deliberately do NOT pick the empty `release`
            // signing config because AGP's `packageRelease` task REQUIRES
            // `storeFile` to be set — if we don't guard, the build fails
            // at the packaging stage with "SigningConfig release is
            // missing required property storeFile". The `takeIf` check
            // ensures we only adopt the release config when its
            // configuration block actually populated the keystore.
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

    // ── Security — EncryptedSharedPreferences (Phase 7 · Step 7.8) ──
    implementation(libs.security.crypto)

    // ── Test dependencies (Phase 9 · Step 9.1) ──
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.truth)
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
                ?: "https://learngermanwith.fun/api"),
            "API_CONFIG_URL" to (props.getProperty("API_CONFIG_URL")
                ?: "https://learngermanwith.fun/api/config"),
            "UPDATE_URL" to (props.getProperty("UPDATE_URL")
                ?: "https://learngermanwith.fun/update"),
            "TELEGRAM_LINK" to (props.getProperty("TELEGRAM_LINK")
                ?: "https://t.me/streamify"),
            "SENTRY_DSN" to (
                signingProps.getProperty("APP_SENTRY_DSN")
                    ?: props.getProperty("SENTRY_DSN")
                    ?: ""
                ),
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
