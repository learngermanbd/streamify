# =====================================================================
# Phase 8 · Step 8.13 → Phase 6 · Step 6.5 — R8/ProGuard keep rules for the
# :admin module. Smaller surface than :app (no Media3, no Room, no Glide,
# no Lottie, no DataStore proto, no Firebase Messaging), so the rules
# below are a focused subset.
# =====================================================================

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- Our application code (all three manifest-declared components) ---
-keep public class com.streamify.admin.StreamifyAdminApp { *; }
-keep public class com.streamify.admin.ui.login.LoginActivity { *; }
-keep public class com.streamify.admin.ui.dashboard.DashboardActivity { *; }

# --- Sentry ---
-keep class io.sentry.android.core.SentryAndroid { *; }
-keep class io.sentry.Sentry { *; }
-keep class io.sentry.SentryOptions { *; }
-keep class io.sentry.protocol.** { *; }
-keep class io.sentry.event.Event { *; }
-dontwarn io.sentry.**
# Same reasoning as :app: admin does not host native NDK.

# --- OkHttp + Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# --- Gson (admin response models use @SerializedName) ---
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
-dontwarn sun.misc.**
-keepnames class com.streamify.admin.data.** { *; }
-keepclassmembers class com.streamify.admin.data.** {
    <fields>;
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- Kotlin metadata + companions ---
-keep class kotlin.Metadata { *; }
# Note: `kotlin.reflect.**` is deliberately NOT kept here (we don't add
# `kotlin-reflect` as a dependency).
-keepclassmembers class **$Companion { *; }

# --- Material / AppCompat / Navigation ---
-keep public class com.google.android.material.** { *; }
-keep public class androidx.appcompat.widget.** { *; }
-keep public class androidx.navigation.fragment.NavHostFragment { *; }
-dontwarn com.google.android.material.**
-dontwarn androidx.appcompat.**
-dontwarn androidx.navigation.**

# --- DataStore Preferences (used for auth-token persistence) ---
-keep class androidx.datastore.preferences.protobuf.** { *; }
-dontwarn androidx.datastore.preferences.protobuf.**
