// AGP 9 bringt Kotlin-Unterstützung selbst mit. Die KGP-Version wird hier
// festgenagelt, damit Compose- und Serialization-Compiler-Plugin (2.4.20)
// exakt zur eingebauten Kotlin-Version passen.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
