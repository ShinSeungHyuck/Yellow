plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.yellow"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.yellow"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation 'com.github.JorenSix:TarsosDSP:v2.4' // JitPack을 통해 TarsosDSP 추가 (버전 확인 필요)

    // UI 관련 기본 라이브러리
    implementation 'androidx.core:core-ktx:1.17.0' // 이미 있을 수 있음
    implementation 'androidx.appcompat:appcompat:1.7.1' // 이미 있을 수 있음
    implementation 'com.google.android.material:material:1.13.0' // 이미 있을 수 있음
    implementation 'androidx.constraintlayout:constraintlayout:2.2.1' // 이미 있을 수 있음
}