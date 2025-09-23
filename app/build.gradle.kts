plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
repositories {
    google()
    mavenCentral()
    maven("https://jitpack.io") // <- JitPack 추가
    flatDir {
        dirs("libs") // libs 폴더 참조
    }
}
android {
    namespace = "com.example.yellow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.yellow"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
        // dataBinding = true   // <layout> 태그 사용했다면 이 줄도 켜야 합니다
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}


dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.11.0")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.2")
    //implementation("com.github.JorenSix:TarsosDSP:2.5")
    //implementation("com.github.JorenSix:TarsosDSP:master-SNAPSHOT")
    //implementation("be.tarsos.dsp:core:2.5")
    //implementation("be.tarsos.dsp:jvm:2.5")
    implementation(files("libs/TarsosDSPKit-release.aar"))
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("com.github.leff-software:midi-android:1.0.0")
}
