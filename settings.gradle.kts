pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // com.reown:android-core and com.reown:sign (Reown / WalletConnect v2)
        // are both published here. Maven Central is the only repo we need.
        mavenCentral()
        // JitPack fallback for legacy transitive artifacts. Safe to keep —
        // the modern Reown 1.6.x line does NOT pull Scarlet, so this is only
        // a defensive net for any future transitive that lives on GitHub.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "My Application"
include(":app")
