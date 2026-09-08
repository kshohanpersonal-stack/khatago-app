/*
 * KhataGo — settings
 *
 * Single Gradle module by design: KhataGo is an offline, on-device application with no
 * backend, no flavours and no product variants, so a well-partitioned package structure inside
 * one module is easier to keep honest than several modules. See docs/ARCHITECTURE.md.
 */
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    // Pinned here so that the whole build resolves exactly one version of the toolchain plugins.
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    id("com.diffplug.spotless") version "6.25.0" apply false
}

rootProject.name = "KhataGo"
include(":app")
