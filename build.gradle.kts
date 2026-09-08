// KhataGo — root build script.
// All real configuration lives in :app. Plugins are version-pinned in settings.gradle.kts.
plugins {
    id("com.diffplug.spotless")
}

spotless {
    kotlin {
        target("app/src/**/*.kt")
        // Formatting is enforced in CI; the rule set is intentionally minimal so that
        // formatting churn never blocks a functional change.
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("xml") {
        target("app/src/main/res/**/*.xml", "app/src/main/AndroidManifest.xml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
