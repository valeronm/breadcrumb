import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
    id("io.gitlab.arturbosch.detekt")
}

// Rules are the detekt defaults minus the style-preference ones (config/detekt/detekt.yml);
// the committed baseline grandfathers findings that predate adoption, so only new findings
// fail. Regenerate after fixing baselined ones: ./gradlew :app:detektBaseline
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
}

// Release signing credentials live in keystore.properties (gitignored). Absent on machines that
// only build debug — release signing is simply skipped there.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.valeronm.breadcrumb"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.valeronm.breadcrumb"
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        // "1.0+16e7a3a", with "-dirty" appended when built from uncommitted changes.
        val gitSha = providers.exec {
            commandLine("git", "describe", "--always", "--dirty")
        }.standardOutput.asText.get().trim()
        versionName = "1.0+$gitSha"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        // SPIKE: Protomaps hosted-API key, read from local.properties (gitignored) so it isn't committed.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "PROTOMAPS_API_KEY", "\"${localProps.getProperty("protomapsApiKey", "")}\"")

        // The developer affordances (track replay) and the build badge the UI reads. Gated on
        // these rather than on BuildConfig.DEBUG, which AGP derives from `debuggable` — so the
        // perf build, whose whole point is not being debuggable, would otherwise lose them.
        buildConfigField("boolean", "DEV_TOOLS", "false")
        buildConfigField("String", "BUILD_LABEL", "\"\"")
    }

    signingConfigs {
        // Upload key: signs builds uploaded to Play; Google re-signs them with the app signing key.
        create("upload") {
            val pw = keystoreProperties.getProperty("uploadStorePassword")
            if (pw != null) {
                storeFile = file(keystoreProperties.getProperty("uploadStoreFile"))
                storePassword = pw
                keyAlias = keystoreProperties.getProperty("uploadKeyAlias")
                // Empty key password means it reuses the store password (PKCS12 / keytool default).
                keyPassword = keystoreProperties.getProperty("uploadKeyPassword")
                    ?.takeIf { it.isNotBlank() } ?: pw
            }
        }
        // App signing key: the app's permanent identity, uploaded to Play App Signing and kept
        // offline. Used locally only to build APKs for distribution outside Play (which install
        // over Play copies). Invoked via -PsignWithAppSigningKey.
        create("appSigning") {
            val pw = keystoreProperties.getProperty("appSigningStorePassword")
            if (pw != null) {
                storeFile = file(keystoreProperties.getProperty("appSigningStoreFile"))
                storePassword = pw
                keyAlias = keystoreProperties.getProperty("appSigningKeyAlias")
                keyPassword = keystoreProperties.getProperty("appSigningKeyPassword")
                    ?.takeIf { it.isNotBlank() } ?: pw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Package native symbol tables (MapLibre) into the bundle so Play can symbolicate
            // native crashes.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
            // Sign with the upload key by default (for Play). Pass -PsignWithAppSigningKey to sign
            // with the app signing key instead, for an APK distributed outside Play. Only applied
            // when that key's credentials are actually present in keystore.properties.
            val signing = if (project.hasProperty("signWithAppSigningKey")) {
                signingConfigs.getByName("appSigning")
            } else {
                signingConfigs.getByName("upload")
            }
            if (signing.storeFile != null) {
                signingConfig = signing
            }
        }
        debug {
            // Lets a debug build install alongside a release build.
            applicationIdSuffix = ".debug"
            // JaCoCo coverage for host unit tests: `./gradlew :app:createDebugUnitTestCoverageReport`.
            enableUnitTestCoverage = true
            buildConfigField("boolean", "DEV_TOOLS", "true")
            buildConfigField("String", "BUILD_LABEL", "\"debug\"")
        }
        /**
         * The build to measure on. Identical to debug except that it isn't debuggable, which is
         * the whole point: `debuggable = true` holds ART's optimizer back, and on the same commit
         * it tripled the track screen's open (~250 ms and 32 dropped frames, against ~80 ms and
         * none) — enough to read as a regression that wasn't there. A debug build cannot answer
         * "is this slow"; this one can.
         *
         * It keeps debug's `.debug` suffix and signing, so `installPerf` replaces the debug app in
         * place with its recorded history intact, and `installDebug` puts the debuggable one back.
         * `adb run-as` and the Compose tooling stop working while it is installed — that is the
         * trade, and it is why this is a separate build type rather than a flag flipped on debug.
         *
         * R8 stays off, so it builds fast and stack traces still map to source. That leaves it a
         * little slower than a real release build, which is the safe direction for a measurement.
         */
        create("perf") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".debug"
            enableUnitTestCoverage = false
            signingConfig = signingConfigs.getByName("debug")
            // It is indistinguishable from a release build on the device — same speed, and with
            // BuildConfig.DEBUG false it would have dropped the dev tools too. So it says which
            // build it is in all three places one can be read: the launcher, the bar badge, and
            // the version row in Settings.
            versionNameSuffix = "-perf"
            buildConfigField("boolean", "DEV_TOOLS", "true")
            buildConfigField("String", "BUILD_LABEL", "\"perf\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric needs the merged resources/manifest.
            all {
                // Full assertion messages and stacks in the console — the default short format
                // reports a coroutine test's failure at its runTest line with no message, which
                // is useless from CI where the HTML report isn't reachable.
                it.testLogging {
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.1.0")

    // Lifecycle + foreground service helpers
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // Compose
    implementation("androidx.activity:activity-compose:1.13.0")
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // Location + Activity Recognition (Google Play Services)
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // MapLibre GL Native renders the recorded tracks on a Protomaps vector basemap (dark or light,
    // following the app theme).
    //
    // The artifact picks the rendering backend, and the backend decides which devices Play will
    // offer the app to: this one merges in a required `android.hardware.vulkan.version` feature,
    // while the `android-sdk-opengl` sibling requires OpenGL ES 3.0 instead. Both are hard
    // filters, so swapping the artifact silently widens or narrows the supported device set —
    // it is a distribution decision, not just a rendering one.
    implementation("org.maplibre.gl:android-sdk:13.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // XmlPullParser implementation for GpxParser unit tests (Android provides one at runtime).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    // Robolectric runs Room (and the SQLite it needs) on the host JVM, so the repository's
    // database rules — the denormalized track aggregates, and which writes invalidate which
    // observed query — are covered by the normal `testDebugUnitTest` run rather than needing a
    // device. Everything above the data layer still has no automated coverage.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    // Streams a backup export in the recorder replay (FixIngestReplayTest). MapLibre's geojson
    // already pulls gson in transitively; declared here so a map-library bump can't take the test
    // suite's JSON reader with it.
    testImplementation("com.google.code.gson:gson:2.10.1")
}
