plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.testconnection.confidence_agent"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.testconnection.confidence_agent.cloudtest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-cloudtest"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Android Studio Run and command-line builds use the same invited cloud app.
        buildConfigField("String", "API_BASE_URL", "\"https://api.yaligo-growth.top\"")
        buildConfigField("boolean", "CLOUD_TEST", "true")
    }

    buildTypes {
        create("localDebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            matchingFallbacks += listOf("debug")
            buildConfigField("String", "API_BASE_URL", "\"http://127.0.0.1:8000\"")
            buildConfigField("boolean", "CLOUD_TEST", "false")
            resValue("string", "app_name", "鸭力Go · 本地")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    add("localDebugImplementation", libs.androidx.compose.ui.test.manifest)
}
