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
            // Debug signing config retained so the suite certificate pin matches.
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
}
