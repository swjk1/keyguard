import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * Release signing, read from `local.properties`.
 *
 * That file is already the machine-local secrets file and is already gitignored, so the upload
 * key does not need a second mechanism. Absent keys are not an error: a release build without
 * them is simply unsigned, which keeps `assembleSoloRelease` usable for checking that R8 has not
 * broken anything on a machine that has no business holding the signing key.
 */
val signingProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val keystorePath: String? = signingProps.getProperty("keyguard.keystore")

android {
    namespace = "com.keyguard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.keyguard.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    /**
     * `solo` is the standalone keyboard: no accounts, no network, nothing to declare on the
     * Play data-safety form. `family` adds parent supervision, which brings the monitoring-tool
     * declaration and Play's Stalkerware policy with it.
     *
     * This is a flavor rather than a runtime check because the difference is what the app
     * *declares*, not what it does — a manifest permission and a policy category are decided at
     * build time or not at all. The runtime gate on a blank `verify_base_url` still exists and
     * still matters; it is what keeps a family build safe when its server is unreachable.
     */
    flavorDimensions += "mode"

    productFlavors {
        create("solo") {
            dimension = "mode"
        }
        create("family") {
            dimension = "mode"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = signingProps.getProperty("keyguard.keystorePassword")
                keyAlias = signingProps.getProperty("keyguard.keyAlias")
                keyPassword = signingProps.getProperty("keyguard.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
        // BuildConfig.DEBUG gates the verification trace log, which must never run in release.
        buildConfig = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(project(":detect"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json)
    testRuntimeOnly(libs.junit.platform.launcher)
}
