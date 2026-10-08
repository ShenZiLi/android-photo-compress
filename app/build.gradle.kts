plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room { schemaDirectory("$projectDir/schemas") }

android {
    namespace = "com.photocompress.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.photocompress.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 37
        versionName = "0.1.36"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val signingStore = providers.environmentVariable("SIGNING_STORE_FILE").orNull
    val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
    val signingAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
    val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
    val hasReleaseSigning = listOf(signingStore, signingStorePassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }
    if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
        require(hasReleaseSigning) { "Release builds require all four SIGNING_* environment variables." }
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(signingStore))
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.heifwriter)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.haze)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
