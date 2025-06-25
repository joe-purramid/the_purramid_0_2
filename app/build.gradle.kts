plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.gradle)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.navigation.safeargs.kotlin)
}

android {
    namespace = "com.example.purramid.thepurramid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.purramid.thepurramid"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/LICENSE.txt" // Note the leading '/' and '+=' operator
            excludes += "/META-INF/NOTICE.txt"
            // Add other excludes if build errors occur due to duplicate files
        }
    }

    // If using Room with KSP, you might need to configure sourcesets
   sourceSets.configureEach { // <- Potentially needed for KSP + Room
        kotlin.srcDir("build/generated/ksp/$name/kotlin")
   }
}

dependencies {

    // annotationProcessor(libs.glide.compiler)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.ar:core:1.48.0")
    implementation(libs.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidsvg)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.emojiTwo)
    implementation(libs.androidx.emojiTwo.views)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.window)
    implementation(libs.appcompat)
    implementation(libs.cardview)
    implementation(libs.colorpicker)
    implementation(libs.constraintlayout)
    implementation(libs.emojiPopup)
    implementation(libs.flexbox)
    implementation(libs.glide.core)
    implementation(libs.gson)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    testImplementation(libs.junit)
    implementation(libs.konfetti.xml) // was libs.androidx.particles
    implementation(libs.room.ktx)
    implementation(libs.room.runtime)
    implementation(libs.sceneview) // instead of sceneform
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("org.locationtech.jts.io:jts-io-common:1.20.0")
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    ksp(libs.glide.compiler)
    ksp(libs.hilt.compiler)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
}