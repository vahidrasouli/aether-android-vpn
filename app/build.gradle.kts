import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val baseVersionName = "1.1.0"
val githubRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.trim()?.toIntOrNull()
val debugVersionCode = githubRunNumber?.coerceAtLeast(1) ?: 1
val debugVersionSuffix = if (githubRunNumber != null) {
    "-debug-b$githubRunNumber"
} else {
    "-debug-local"
}

android {
    namespace = "io.aethervpn.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.aethervpn.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = baseVersionName

        ndk {
            // Primary target: ARM64 Android devices (minimizes APK size)
            abiFilters += listOf("arm64-v8a")
        }

        // Strip unused AndroidX / Material library locales to shrink APK
        resourceConfigurations += listOf("en", "fa")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            versionNameSuffix = debugVersionSuffix
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Extract libpsiphon-tunnel-core.so to nativeLibraryDir with +x permissions (required for execve() on Android 10+)
            // and compress .so files inside the APK.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed", "standardError")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.BaseVariantOutputImpl }
            .forEach { output ->
                output.outputFileName = "aether-android-arm64-v${variant.versionName}.apk"
            }
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(debugVersionCode)
        }
    }
}

dependencies {
    // Minimal lightweight AndroidX footprint (no Jetpack Compose runtime overhead)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
