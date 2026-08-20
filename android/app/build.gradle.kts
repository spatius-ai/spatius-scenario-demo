import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.spatialwalk.scenes"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.spatialwalk.scenes"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // The demo is signed with the debug key so it installs as-is, with no
            // release keystore to maintain. Swap in a real signing config before
            // shipping to a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}


dependencies {
    implementation("ai.spatius:avatarkit:1.3.3")
    // RTC driving. avatarkit-rtc declares both the main SDK and Agora as compileOnly,
    // so the host has to bring them in explicitly.
    implementation("ai.spatius:avatarkit-rtc:1.0.0")
    implementation("io.agora.rtc:full-sdk:4.6.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // The student's camera preview
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.accompanist.permissions)
    // CameraX's ProcessCameraProvider returns a ListenableFuture.
    implementation(libs.guava)

    // Exchanges credentials with the agent backend for an Agora connection
    implementation(libs.okhttp)
    // Cover art for the characters comes from the backend as a URL, so the picker needs
    // to load images off the network rather than out of resources.
    implementation("io.coil-kt:coil-compose:2.7.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
