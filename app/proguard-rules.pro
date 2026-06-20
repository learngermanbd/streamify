# =========================================================================
# Phase 7 · Step 7.3 — Aggressive R8 full-mode obfuscation
# =========================================================================
#
# R8 full mode is enabled in gradle.properties:
#   android.enableR8.fullMode=true
#
# This file configures:
#   1. Aggressive optimization + obfuscation flags
#   2. CJK rename dictionary (visually confusing class/method names)
#   3. Source-file attribute stripping (removes file:line from stack traces)
#   4. Keep rules for reflection-driven libraries and security classes
#
# Debugging: use `-printusage usage.txt` to see what R8 strips.
# =========================================================================

# ── Aggressive optimization ──────────────────────────────────────────────
# Repackage all classes into a single root package (hides real structure).
-repackageclasses ''
# Broaden access modifiers to enable more optimizations.
-allowaccessmodification
# Rename methods with different signatures to the same name.
-overloadaggressively
# Merge interfaces when implementation classes overlap.
-mergeinterfacesaggressively

# ── CJK rename dictionary ────────────────────────────────────────────────
# Replaces standard a/b/c class/method names with CJK characters.
# Breaks many decompilers' display logic and creates massive cognitive
# burden for reverse engineers.
-obfuscationdictionary proguard-cjk-dictionary.txt
-classobfuscationdictionary proguard-cjk-dictionary.txt
-packageobfuscationdictionary proguard-cjk-dictionary.txt

# ── Strip source-file information ────────────────────────────────────────
# Removes SourceFile + LineNumberTable attributes from class files.
# Stack traces will show obfuscated names with no file/line info.
# Sentry's uploaded mapping.txt still allows deobfuscation server-side.
-keepattributes !SourceFile,!LineNumberTable
-renamesourcefileattribute ''

# ── Debugging (uncomment to inspect what R8 strips) ──────────────────────
# -printusage build/r8-usage.txt
# -printseeds build/r8-seeds.txt
# -printmapping build/r8-mapping.txt

# ── Keep rules for reflection-driven libraries ──────────────────────────

# ── Gson ─────────────────────────────────────────────────────────────────
# @SerializedName fields are accessed via reflection.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ── OkHttp platform ─────────────────────────────────────────────────────
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Room (KSP-generated code is already kept, but DAOs may use reflection)
-keep class * extends androidx.room.RoomDatabase
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# ── Sentry ───────────────────────────────────────────────────────────────
# Keep Sentry event classes that are serialized via reflection.
-keep class io.sentry.SentryEvent { *; }
-keep class io.sentry.protocol.** { *; }

# ── Phase 7 · Step 7.2 — Security package ────────────────────────────────
# EncryptedConstants is accessed directly (no reflection) so R8 will keep
# it automatically.  However, the field names serve as the encryption key
# lookup table — if R8 renames them the app crashes at runtime.  We keep
# the class and its fields but allow method-level obfuscation.
-keep class com.streamify.app.security.EncryptedConstants { *; }
-keep class com.streamify.app.security.EncryptedConstants$Entry { *; }

# RuntimeStringProvider's ENTRIES keys are string literals, not reflection.
# KeystoreManager, StringEncryptor, SecurityModule — direct calls only.
# No additional keep rules needed for those classes.

# ── Data models (Phase 2) ────────────────────────────────────────────────
# Keep all data classes that are deserialized from JSON via org.json.
-keep class com.streamify.app.data.models.** { *; }

# ── Crash handler ────────────────────────────────────────────────────────
# CrashActivity is launched via Intent from the UncaughtExceptionHandler.
-keep class com.streamify.app.data.crash.CrashActivity { *; }

# ── Phase 7 · Step 7.4 — Native JNI bridge ───────────────────────────────
# NativeSecurityManager's companion static method bytesToHex is called
# from JNI (RegisterNatives).  The class and its native methods must
# survive R8 renaming.  The actual JNI bindings use RegisterNatives in
# JNI_OnLoad, so the standard JNI naming convention does not apply.
-keep class com.streamify.app.security.NativeSecurityManager {
    native <methods>;
    static java.lang.String bytesToHex(byte[]);
}

# ── Phase 7 · Step 7.5 — Integrity / Tamper / SelfHealing ──────────────
# IntegrityChecker and TamperDetector use PackageManager reflection
# and ZipFile iteration (no reflection-based field access).  SelfHealing
# references BuildConfig.DEBUG and Sentry.captureEvent (kept by Sentry
# rules above).  No additional keep rules needed — direct calls only.
