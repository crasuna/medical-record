plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    id("com.google.devtools.ksp")
}

val releaseStoreFile = providers.environmentVariable("MEDICAL_RECORD_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("MEDICAL_RECORD_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MEDICAL_RECORD_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MEDICAL_RECORD_KEY_PASSWORD").orNull
val releaseSigningInputs = linkedMapOf(
    "MEDICAL_RECORD_STORE_FILE" to releaseStoreFile,
    "MEDICAL_RECORD_STORE_PASSWORD" to releaseStorePassword,
    "MEDICAL_RECORD_KEY_ALIAS" to releaseKeyAlias,
    "MEDICAL_RECORD_KEY_PASSWORD" to releaseKeyPassword,
)
val configuredReleaseSigningInputs = releaseSigningInputs.filterValues { !it.isNullOrBlank() }
if (configuredReleaseSigningInputs.isNotEmpty() &&
    configuredReleaseSigningInputs.size != releaseSigningInputs.size
) {
    val missingNames = releaseSigningInputs.keys - configuredReleaseSigningInputs.keys
    throw GradleException(
        "Incomplete external release signing configuration. Missing: ${missingNames.joinToString()}.",
    )
}
val hasReleaseSigning = configuredReleaseSigningInputs.size == releaseSigningInputs.size

android {
    namespace = "com.loveluke.medicalrecord"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.loveluke.medicalrecord"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("externalRelease") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("externalRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.jvmArgs("--enable-native-access=ALL-UNNAMED")
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.navigation.suite)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.framework)
    implementation(libs.sqlcipher.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
