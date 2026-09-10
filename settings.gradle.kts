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

// The installable APK: contains both the IME service and the setup/settings UI.
//
// The plan sketched these as separate :keyboard and :app modules, but an IME and its
// container UI must ship in a single APK, so a module boundary between them would add
// indirection without buying isolation. Kept as one module until something needs to be
// shared with a second artifact.
include(":app")
