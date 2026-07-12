import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
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
        versionCode = 5
        versionName = "1.1"

        // SPIKE: Protomaps hosted-API key, read from local.properties (gitignored) so it isn't committed.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField("String", "PROTOMAPS_API_KEY", "\"${localProps.getProperty("protomapsApiKey", "")}\"")
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

    // MapLibre GL Native renders the recorded tracks on a Protomaps dark vector basemap.
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // XmlPullParser implementation for GpxParser unit tests (Android provides one at runtime).
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
