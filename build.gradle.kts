buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // AGP built-in Kotlin uses its bundled KGP unless a higher compatible
        // version is placed on the buildscript classpath.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.3.11")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
}
