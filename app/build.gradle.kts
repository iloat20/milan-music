import java.util.Properties

// Keystore credentials live in local.properties (machine-local, never committed):
//   KEYSTORE_STORE_PASSWORD=...
//   KEYSTORE_KEY_PASSWORD=...
// A machine without those entries builds debug fine; release signing fails with a
// keystore error until the two lines are added.
val keystoreProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.milan.music"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.milan.music"
        minSdk = 26
        targetSdk = 37
        versionCode = 50
        versionName = "1.0.49"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/milanmusic-release.keystore")
            storePassword = keystoreProps.getProperty("KEYSTORE_STORE_PASSWORD", "")
            keyAlias = "milanmusic"
            keyPassword = keystoreProps.getProperty("KEYSTORE_KEY_PASSWORD", "")
            // v3 is what allows key rotation if the keystore is ever replaced; v4
            // signs the .idsig side file that incremental installs use.
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            // R8 strips the unused bulk of material-icons-extended and unreferenced
            // code; without it the APK carries the entire extended icon set.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            // arm64-v8a only. The app has no native code of its own: the two .so files
            // in the package come from androidx (graphics-path, datastore shared counter)
            // and ship per ABI. Filtering to 64-bit ARM drops the 32-bit and x86 copies —
            // ~50 KB — and makes the shipped artifact's ABI set explicit instead of
            // inherited from whichever transitive dependency added a native lib next.
            //
            // Scoped to release on purpose: `debug` keeps all four ABIs so x86_64
            // emulators still install. The trade-off is real — release no longer installs
            // on 32-bit-only devices, which is acceptable above minSdk 26 in 2026.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.haze)
    implementation(libs.haze.blur)
}
