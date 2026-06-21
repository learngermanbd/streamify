// Top-level build file. Sub-project/plugin configuration per module.
// Step 1.1: declared plugin coordinates.
// Step 1.2: added org.jetbrains.kotlin.kapt (for Room compiler).
// 2026-06-21 — toolchain migration. The previous
// `org.gradle.java.home=C:/Program Files/Android/Android Studio/jbr`
// pin has been removed from `gradle.properties` and replaced with
// `org.gradle.java.installations.paths` pointing at Temurin JDK 17,
// plus `org.gradle.java.installations.auto-download=false` to fail
// loud if Temurin 17 ever becomes unreachable.
//
// Per-module bytecode is pinned to 17 via each module's
//   compileOptions { sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17 }
//   kotlinOptions { jvmTarget = "17" }
// and the build's effective JDK is the Temurin 17 install discovered
// via `installations.paths` (which Gradle honours for both daemon
// JVM spawning AND toolchain resolution against AGP's compile-tool
// spec). No explicit `java { toolchain { ... } }` block is declared
// at root or per-module because Gradle Kotlin DSL in this project's
// wrapper version doesn't expose `org.gradle.api.JavaLanguageVersion`
// at script compile time (`Unresolved reference`), and the
// `subprojects { java { ... } }` hoisted form calls `java {}` on the
// Subprojects container (no such extension) rather than each
// subproject. The installations.paths + auto-download=false pair is
// sufficient to satisfy the user's intent ("switch daemon toolchain
// to gradle") without that import race.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.ksp)                 apply false
    alias(libs.plugins.google.services)     apply false
}
