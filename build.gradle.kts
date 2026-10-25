// Top-level build file. Plugin versions are pinned here and applied per-module.
plugins {
    // AGP supports only a range of Gradle versions, so this moves with the wrapper
    // (gradle/wrapper/gradle-wrapper.properties).
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
    // Keep in step with the androidx.room:* coordinates in app/build.gradle.kts — a plugin and
    // compiler that disagree are not reported loudly.
    id("androidx.room") version "2.8.4" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
    // Detekt 1.x prints Gradle's "ReportingExtension.file(String) has been deprecated" warning from
    // its own apply(), before any config of ours runs (detekt#8452). Only detekt 2.x fixes it, and
    // 2.x renames the packages to `dev.detekt` and reorganizes the rule sets. This pin moves when
    // detekt 2.0 is stable, or when Gradle 10, which removes the API, is on the table.
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
}
