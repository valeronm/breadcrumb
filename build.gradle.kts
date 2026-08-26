// Top-level build file. Plugin versions are pinned here and applied per-module.
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    // Keep in step with the androidx.room:* coordinates in app/build.gradle.kts — a plugin and
    // compiler that disagree are not reported loudly.
    id("androidx.room") version "2.8.4" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}
