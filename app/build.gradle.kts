import java.io.FileInputStream
import java.util.Properties

/*
 * KhataGo — application module.
 *
 * Design constraints enforced here:
 *  - no network stack at all: KhataGo never needs the INTERNET permission
 *  - no ads / no tracking / no analytics / no billing libraries (see docs/PRIVACY.md)
 *  - release signing is only enabled when local or CI secrets provide the keystore;
 *    no keystore or password is ever committed to this repository
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// Release signing (opt-in). Sources, in priority order:
//  1. keystore.properties in the repository root  — git-ignored, developer machines
//  2. KHATAGO_KEYSTORE_BASE64 + *_PASSWORD / *_ALIAS env vars — CI, from GitHub Secrets
// With neither present, assembleRelease still produces an unsigned, zipalign-able APK so
// the release pipeline can be verified without inventing credentials.
// ---------------------------------------------------------------------------
val keystorePropsFile: File = rootProject.file("keystore.properties")
val keystoreProps: Properties = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}
fun signingProp(name: String, envName: String): String? =
    keystoreProps.getProperty(name) ?: System.getenv(envName)

val releaseSigningAvailable: Boolean =
    signingProp("storeFile", "KHATAGO_KEYSTORE_FILE") != null &&
        signingProp("storePassword", "KHATAGO_KEYSTORE_PASSWORD") != null &&
        signingProp("keyAlias", "KHATAGO_KEY_ALIAS") != null &&
        signingProp("keyPassword", "KHATAGO_KEY_PASSWORD") != null

/**
 * CI passes the keystore as base64 so it never lands in the working tree. It is written to the
 * build directory only, which is deleted by `./gradlew clean`, and never committed.
 */
fun resolvedKeystoreFile(): File {
    val direct = signingProp("storeFile", "KHATAGO_KEYSTORE_FILE")
    if (direct != null && keystorePropsFile.exists()) return rootProject.file(direct)
    if (direct != null) return file(direct)

    val encoded = requireNotNull(System.getenv("KHATAGO_KEYSTORE_BASE64")) {
        "KHATAGO_KEYSTORE_BASE64 is not set"
    }
    val target = layout.buildDirectory.file("keystore/khatago-release.keystore").get().asFile
    target.parentFile?.mkdirs()
    target.writeBytes(java.util.Base64.getMimeDecoder().decode(encoded))
    return target
}

android {
    namespace = "com.khatago.finance"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.khatago.finance"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        resourceConfigurations += listOf("en")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("khatagoRelease") {
                storeFile = resolvedKeystoreFile()
                storePassword = signingProp("storePassword", "KHATAGO_KEYSTORE_PASSWORD")
                keyAlias = signingProp("keyAlias", "KHATAGO_KEY_ALIAS")
                keyPassword = signingProp("keyPassword", "KHATAGO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            // R8 shrinking. KhataGo is reflection-free apart from Room/kotlinx-serialization,
            // both of which ship consumer rules; app rules are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("khatagoRelease")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Room DAO and DataStore tests run on the JVM under Robolectric, so CI can execute
            // the whole suite (except the emulator-only UI checks) without a device.
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("-Xmx1536m")
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    correctErrorTypes = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    testImplementation(composeBom)

    // --- language / coroutines -------------------------------------------------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // --- AndroidX core ---------------------------------------------------------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // --- lifecycle / state -----------------------------------------------------
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // --- Compose / Material 3 --------------------------------------------------
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // Icons Extended, not just the core set: KhataGo's module tiles, due-status affordances and
    // detail actions use ~35 glyphs (Wallet, Inventory, ReceiptLong, Verified, TrendingUp, …) that
    // material-icons-core does not ship. Version comes from the Compose BOM. R8 strips the unused
    // remainder, so this costs build time and nothing at runtime.
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.animation:animation")

    // --- navigation ------------------------------------------------------------
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // --- persistence -----------------------------------------------------------
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // NOTE: no DataStore. KhataGo's settings that a *query* needs (widget visibility, reminders,
    // sample-data flag) live in the Room `app_settings` table so they participate in the same
    // transaction and the same backup as the ledger; the security store is a private
    // SharedPreferences file that must NOT be in a backup. A third key-value store would be a third
    // place for state to disagree.

    // --- background work + local notifications ---------------------------------
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // --- optional biometric app lock -------------------------------------------
    implementation("androidx.biometric:biometric:1.1.0")

    // --- unit tests ------------------------------------------------------------
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("app.cash.turbine:turbine:1.1.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")

    // --- instrumented tests ----------------------------------------------------
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("androidx.navigation:navigation-testing:2.8.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
