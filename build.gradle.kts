// Top-level build file. AGP 9.0+ bundles Kotlin natively and explicitly
// rejects the `org.jetbrains.kotlin.android` plugin. The compiler version
// is locked to whatever AGP 9.2.1 ships (Kotlin 2.2.0). Library choices
// (e.g. Reown version) must be compatible with that bundled compiler.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.google.services) apply false
}
