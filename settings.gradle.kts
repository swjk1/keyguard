pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "keyguard"

// Pure-Kotlin detection engine. Deliberately has zero Android dependencies so its
// golden-corpus tests run on the JVM in milliseconds, and so the eventual iOS port
// only has to reimplement a thin matcher against the same rule-pack JSON.
include(":detect")

// On-device model inference: tokenizer, ONNX Runtime session, BIO decoding, and the Kotlin
// side of the risk rule table.
//
// Separate from :detect on purpose. :detect is pure-Kotlin JVM so its golden-corpus tests run
// in milliseconds and an iOS port would only have to reimplement a thin matcher; ONNX Runtime
// is an Android AAR, and pulling it into :detect would take that away permanently. Everything
// in here that can be pure Kotlin is, so the tokenizer and rule engine still test on the JVM.
include(":infer")

// The installable APK: contains both the IME service and the setup/settings UI.
//
// The plan sketched these as separate :keyboard and :app modules, but an IME and its
// container UI must ship in a single APK, so a module boundary between them would add
// indirection without buying isolation. Kept as one module until something needs to be
// shared with a second artifact.
include(":app")
