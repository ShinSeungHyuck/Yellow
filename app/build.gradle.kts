plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Java 17 호환성을 위한 Bouncy Castle 라이브러리 버전 강제 지정
configurations.all {
    resolutionStrategy {
        dependencySubstitution {
            substitute(module("org.bouncycastle:bcprov-jdk18on")).using(module("org.bouncycastle:bcprov-jdk15to18:1.77"))
        }
    }
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
    implementation(files("libs/TarsosDSPKit-release.aar"))
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
}
