// Top-level build file. Sub-project/plugin configuration per module.
// Step 1.1: declared plugin coordinates.
// Step 1.2: added org.jetbrains.kotlin.kapt (for Room compiler).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android)      apply false
    alias(libs.plugins.ksp)                 apply false
    alias(libs.plugins.google.services)     apply false
}
