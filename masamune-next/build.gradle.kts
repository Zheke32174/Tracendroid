import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * masamune-next — a from-scratch on-device AI harness module.
 *
 * Deliberate omissions (each one is a build blocker in :app, see the salvage notes):
 *   - no `project(":terminal")`      : that Gradle submodule is checked out empty.
 *   - no kapt / no ObjectBox         : kapt's javac reflection breaks on JDK 21.
 *   - no externalNativeBuild/ndk     : every native third-party tree is empty.
 *   - no fileTree("libs")            : app/libs contains only .keep (ffmpeg-kit absent).
 *   - no assets/ APKs, no keystores  : nothing this app replaces ships inside it.
 *
 * Room is used through KSP only. Persistence and JSON use androidx.room and org.json
 * (framework), so R8 has no reflective-serialization surface to break.
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.pleiades.masamune"
    compileSdk = 35

    defaultConfig {
        // Same applicationId as the old module, so installing this replaces it on device.
        applicationId = "com.ai.assistance.operit"
        minSdk = 26
        targetSdk = 34
        // Old module is versionCode 41; must be higher to install over it.
        versionCode = 42
        versionName = "2.0.0-next"
        vectorDrawables { useSupportLibrary = true }
    }

    /**
     * The SUITE debug keystore, explicitly.
     *
     * This block did not exist, and the comment below it claimed "Debug signing config retained
     * so the suite certificate pin matches". It did not match. With no signingConfigs block,
     * AGP falls back to the per-developer `~/.android/debug.keystore`, so this module signed
     * with cert 4a25b3af… while every other app in the suite signs with aba68a81… — measured
     * with apksigner, not assumed.
     *
     * That is not cosmetic. Yojimbo brokers privilege to siblings by SIGNATURE MATCH, and
     * SuiteAttestation cross-verifies peers the same way, so an app signed with a different key
     * can never be a suite sibling no matter what its manifest declares. It would also have
     * been invisible until the first time privilege brokering was tried on a device.
     *
     * The keystore is committed on purpose (see keystore/README.md) precisely so any developer's
     * `assembleDebug` produces a cert matching SuitePins.DEBUG_CERT_SHA256.
     */
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Honour -PminifyDebug=true from the build command.
            isMinifyEnabled =
                (project.findProperty("minifyDebug") as String?)?.toBoolean() ?: false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signs with the SUITE keystore configured above, so the cert digest matches
            // SuitePins.DEBUG_CERT_SHA256 and this app can act as a suite sibling.
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
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

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // org.json ships in the Android framework (android.jar) but is a THROW-ing stub in local
    // JVM unit tests ("Method ... not mocked"). The flow runtime's FiberCodec persists through
    // org.json, so its round-trip test needs a REAL implementation on the test classpath. This
    // is test-only; the app still uses the framework org.json on device. Preferred over
    // testOptions.unitTests.returnDefaultValues, which would make put()/get() no-ops and turn a
    // round-trip assertion into one that proves nothing.
    testImplementation("org.json:json:20240303")
}
