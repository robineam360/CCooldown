plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.robin.claudeusage"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.robin.claudeusage"
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.12"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    // QR token import (bundles the camera capture activity + permission flow).
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // Custom Tabs for the in-app OAuth sign-in browser trip.
    implementation("androidx.browser:browser:1.8.0")

    testImplementation("junit:junit:4.13.2")
}
