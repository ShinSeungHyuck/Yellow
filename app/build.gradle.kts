plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id ("org.jetbrains.kotlin.plugin.serialization") version "2.2.0"
}

android {
    namespace = "com.example.yellow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.yellow"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    implementation("androidx.navigation:navigation-fragment-ktx:2.7.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.2")

    // AAR (TarsosDSPKit)
    implementation(files("libs/TarsosDSPKit-release.aar"))
    //implementation files('libs/TarsosDSPKit-release.aar')

    // Ktor (Supabase 기반 라이브러리 요구)
    implementation("io.ktor:ktor-client-android:2.3.11")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")

    // ✅ Supabase (정식 그룹 ID / 최신 3.2.6)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.6"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-android:3.2.1")
    implementation("io.ktor:ktor-client-core:3.2.1")
    implementation("io.ktor:ktor-utils:3.2.1")

    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
