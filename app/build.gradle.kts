import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
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

// Robolectric's native runtime ships no Linux arm64 build, so on an arm64 box every Room-backed
// test dies in setUpApplicationState whatever the change. Forking the test worker — and only the
// worker, so compilation stays native — into an x86_64 JVM under qemu makes them runnable there.
// Opt-in via -PqemuJdk, because emulation costs the several hundred native tests wall-clock they
// have no need to spend. Provision the JVM first with :app:provisionQemuTestJdk.
val qemuJdkRelease = "jdk-21.0.12+8"
val qemuJdkArchive = "OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz"
val qemuJdkSha256 = "e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370"
val qemuSysroot = providers.gradleProperty("qemuSysroot").getOrElse("/usr/x86_64-linux-gnu")

// Under the Gradle user home rather than a path in this build, so it is shared between checkouts
// and lands wherever GRADLE_USER_HOME points — the same place Gradle's own toolchain
// auto-provisioning keeps JDKs.
val qemuJdkDir = File(gradle.gradleUserHomeDir, "jdks/temurin-$qemuJdkRelease-linux-x64-qemu")

// The launcher rather than the home, because that is what a JVM reads its own java.home from.
val qemuJavaLauncher: File? =
    providers.gradleProperty("qemuJdk").orNull
        ?.ifBlank { qemuJdkDir.path }
        ?.let { File(it, "bin/java") }
        ?.also { launcher ->
            require(launcher.exists()) {
                "No x86_64 test JDK at ${launcher.parentFile.parent}. Provision one with " +
                    "`./gradlew :app:provisionQemuTestJdk` (downloads ~200 MB), or point -PqemuJdk " +
                    "at an existing one."
            }
        }

// Not a toolchain: Gradle's JavaToolchainSpec cannot express a foreign architecture, and the
// foojay resolver only ever offers builds for the host's own.
tasks.register("provisionQemuTestJdk") {
    group = "build setup"
    description = "Downloads the x86_64 JDK that -PqemuJdk forks test workers into."
    // Captured as values, not read through the enclosing script: a task action that touches the
    // script instance holds a Project reference and fails to serialize for the configuration cache.
    val target = qemuJdkDir
    val sysroot = qemuSysroot
    val archive = qemuJdkArchive
    val sha256 = qemuJdkSha256
    val url = "https://github.com/adoptium/temurin21-binaries/releases/download/" +
        "${qemuJdkRelease.replace("+", "%2B")}/$archive"
    val qemuOverride = providers.gradleProperty("qemuBin").orNull
    val qemu = when (qemuOverride) {
        null ->
            (System.getenv("PATH")?.split(File.pathSeparator).orEmpty() + "/usr/bin")
                .map { File(it, "qemu-x86_64") }
                .firstOrNull(File::exists)
        else -> File(qemuOverride).takeIf(File::exists)
    }
    // The emulator and sysroot are baked into the generated wrapper, so they belong in the
    // up-to-date check as much as the archive does — without them, changing one re-runs green
    // against a wrapper still pointing at the old one.
    inputs.property("qemu", qemu?.path.orEmpty())
    inputs.property("sysroot", sysroot)
    inputs.property("sha256", sha256)
    outputs.dir(target)
    doLast {
        val os = System.getProperty("os.name")
        val arch = System.getProperty("os.arch")
        check(os == "Linux" && arch !in setOf("amd64", "x86_64")) {
            "Nothing to provision: Robolectric's native runtime already covers $arch on $os."
        }
        val emulator = checkNotNull(qemu) {
            "qemu-x86_64 not found on PATH. On Debian/Ubuntu: sudo apt install qemu-user " +
                "(or point -PqemuBin at one)"
        }
        check(File(sysroot, "lib/ld-linux-x86-64.so.2").exists()) {
            "No x86_64 loader under $sysroot. On Debian/Ubuntu: sudo apt install " +
                "libc6-amd64-cross libstdc++6-amd64-cross libgcc-s1-amd64-cross " +
                "(or set -PqemuSysroot)"
        }

        // Staged beside the destination rather than in java.io.tmpdir, which is commonly a tmpfs
        // and would hold the whole archive in RAM.
        target.parentFile.mkdirs()
        val tarball = File.createTempFile("temurin-x64", ".tar.gz", target.parentFile)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(URI(url).toURL().openStream(), digest).use { source ->
                tarball.outputStream().use(source::copyTo)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "Checksum mismatch for $archive: expected $sha256, got $actual" }

            target.deleteRecursively()
            target.mkdirs()
            // tar rather than Gradle's tarTree, which flattens symlinks into empty files — the
            // JDK's legal/ tree is built out of them.
            val untar = ProcessBuilder(
                "tar", "-xzf", tarball.path, "-C", target.path, "--strip-components=1",
            ).inheritIO().start()
            check(untar.waitFor() == 0) { "Extracting $archive failed" }
        } finally {
            tarball.delete()
        }

        // Two constraints, neither visible from the file this writes. The -L prefix is baked in
        // rather than left to QEMU_LD_PREFIX because Gradle probes the executable while
        // *configuring* the Test task, in an environment it passes no task env vars to. And the
        // wrapper has to replace bin/java in place because a JVM derives java.home from its own
        // executable's path — a launcher in a directory of its own resolves to a home with no JDK
        // under it and never bootstraps.
        val launcher = File(target, "bin/java")
        val real = File(target, "bin/java.real")
        launcher.renameTo(real)
        launcher.writeText("#!/bin/sh\nexec ${emulator.path} -L $sysroot ${real.path} \"${'$'}@\"\n")
        launcher.setExecutable(true)
        logger.lifecycle("Provisioned $target — run tests with -PqemuJdk")
    }
}

android {
    namespace = "io.github.valeronm.breadcrumb"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.valeronm.breadcrumb"
        minSdk = 26
        targetSdk = 37
        versionCode = 21
        // major.minor.patch, bumped by hand alongside versionCode when preparing an upload. Which
        // part is decided from the release notes' own bucketing of the range, so the number and the
        // text are one judgement — docs/release-notes-guide.md, "Which part to bump", which also
        // holds the release flow. The release workflow checks the tag against this line.
        versionName = "1.0.0"
        // "16e7a3a", with "-dirty" appended when built from uncommitted changes. It identifies the
        // commit a build came from, which the version name cannot: that is what the app is called,
        // and a hash means nothing to the reader it is shown to. So it reaches the log instead —
        // where the question is which build produced a line — and nothing on screen.
        val gitSha = providers.exec {
            commandLine("git", "describe", "--always", "--dirty")
        }.standardOutput.asText.get().trim()
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
        buildConfigField("boolean", "SHOW_BUILD_BADGE", "false")
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
            buildConfigField("boolean", "SHOW_BUILD_BADGE", "true")
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
            // build it is wherever one can be read: the launcher label, the bar badge below, and
            // the version row in Settings, which states the variant beside the version.
            buildConfigField("boolean", "DEV_TOOLS", "true")
            buildConfigField("boolean", "SHOW_BUILD_BADGE", "true")
        }
        /**
         * The build to *shoot* from: an install of its own, with an empty database, that the demo
         * history `tools/generate_demo_history.py` writes can be restored into — Restore is offered
         * only on an empty timeline, and neither the release nor the debug install may be cleared
         * to get one.
         *
         * It takes `.demo` rather than debug's suffix, which is the whole point: `perf` reuses
         * `.debug` to *replace* the debug app, while this one has to sit beside both. Everything
         * else follows from what a screenshot must not contain — [BuildConfig.SHOW_BUILD_BADGE] is
         * off so no badge is drawn in the top bar, and DEV_TOOLS is off so the replay control and the
         * map's zoom readout stay out of frame. Not debuggable, so scrolling is at release speed.
         *
         * It keeps `main`'s launcher icon rather than taking a marked one like debug: the icon is
         * drawn in the foreground-service notification and the recents switcher, both of which a
         * screenshot can include. Which build it is stays readable where a screenshot won't reach —
         * the launcher label and the version row in Settings.
         */
        create("demo") {
            initWith(getByName("debug"))
            isDebuggable = false
            applicationIdSuffix = ".demo"
            enableUnitTestCoverage = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "DEV_TOOLS", "false")
            buildConfigField("boolean", "SHOW_BUILD_BADGE", "false")
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
                // ResourceHygieneTest reads res/values* off the disk rather than through the
                // resource table, and Gradle cannot see that: without this the task stays
                // up-to-date through an XML-only edit, so the guard reports green on the one change
                // it exists to check. **Any test that reads files off disk must declare them
                // here** — a drawable is excluded because none does, not because none could.
                it.inputs.files(
                    layout.projectDirectory.dir("src/main/res").asFileTree
                        .matching { include("values*/**/*.xml") },
                )
                    .withPropertyName("stringResources")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                qemuJavaLauncher?.let { launcher -> it.executable = launcher.path }
                // Each fork pays the Robolectric sandbox + Compose-harness warm-up on its own,
                // so forks trade total CPU for wall clock — and past a point they trade it back:
                // the Robolectric workers are multi-threaded (doubly so under qemu), and measured
                // spans on a 10-core box were 174 s at 1 fork, 135 s at 2, 109 s at 3, 141 s at 5.
                // Scaled by cores so a small CI runner is not oversubscribed into the same cliff.
                it.maxParallelForks = (Runtime.getRuntime().availableProcessors() / 3).coerceIn(1, 3)
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Through the Room plugin rather than a bare `room.schemaLocation` KSP arg. The plugin tracks this
// committed directory as an annotation-processing *input*, so deleting a schema re-derives it,
// where the arg wrote the file as a side effect nothing tracked at all and an up-to-date or cached
// compile left it missing or stale with no build able to notice. It also keeps the absolute path
// out of the processor's cache key, which the arg form put there — costing a miss on every
// checkout at a different path. What the exported files are for is in CLAUDE.md.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.documentfile:documentfile:1.1.0")

    // The app lock's prompt. It hosts itself in a fragment, which is why MainActivity is a
    // FragmentActivity rather than a bare ComponentActivity — the only reason.
    implementation("androidx.biometric:biometric:1.1.0")

    // Lifecycle + foreground service helpers
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    // Compose
    implementation("androidx.activity:activity-compose:1.13.0")
    // Clicks play the platform interaction sound, which the device's touch-sounds setting
    // governs. Left on rather than opted out of with SoundEffectOnInteraction: that setting
    // already answers this for every app on the phone, and an opt-out here would overrule it.
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
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
    // device. It also hosts the Compose harness below; above the timeline's rows, nothing is
    // covered automatically.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    // Compose's test harness — see `TimelineRowTest`, which explains what it buys. The BOM is
    // repeated because `ui-test-junit4` is declared without a version.
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // The empty activity the compose rule launches into, and it has to be `debugImplementation`:
    // on `testImplementation` the entry does reach the unit-test merged manifest, and the rule
    // still fails to resolve the activity — measured, not reasoned. The cost is that it lands in
    // the debug APK, where `src/debug/AndroidManifest.xml` closes it (the library declares it
    // exported, which is wrong for anything installed on a phone).
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
