plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val aiAbi = providers.gradleProperty("aiAbi").orNull
require(aiAbi == null || aiAbi in setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
    "Set aiAbi to arm64-v8a, armeabi-v7a, x86_64, or x86."
}

android {
    namespace = "dev.aw.engineremotecontroller"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.aw.engineremotecontroller"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        if (aiAbi != null) ndk.abiFilters.add(aiAbi)

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        noCompress += "onnx"
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
