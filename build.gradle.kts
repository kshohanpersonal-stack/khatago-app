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
    // No `format("xml")` block: a named format with only these two steps would restate nothing
    // useful, and one with *no* step at all is a Spotless runtime error. Resource XML is checked by
    // `tools/check_braces.py`-adjacent review and by Lint, not by a formatter.
}

// NOTE: no `clean` task is registered here. `com.android.application` applied at the root already
// provides one, and `tasks.register<Delete>("clean")` fails the *whole configuration phase* with
// "Cannot add task 'clean' as a task with that name already exists" — which looks nothing like a
// formatting problem and kills every task in the build, tests included.
