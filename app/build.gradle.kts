plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yolotouchhelp.aimbot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yolotouchhelp.aimbot"
        minSdk = 31
        targetSdk = 35
        versionCode = 11
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    flavorDimensions += "variant"
    productFlavors {
        create("infer") {
            dimension = "variant"
            applicationId = "com.yolotouchhelp.aimbot.infer"
            versionNameSuffix = "-infer"
            buildConfigField("boolean", "IS_INFER", "true")
            buildConfigField("boolean", "IS_HOST", "false")
        }
        create("host") {
            dimension = "variant"
            applicationId = "com.yolotouchhelp.aimbot.host"
            versionNameSuffix = "-host"
            buildConfigField("boolean", "IS_INFER", "false")
            buildConfigField("boolean", "IS_HOST", "true")
        }
    }

    signingConfigs {
        if (file("release.jks").exists()) {
            create("release") {
                storeFile = file("release.jks")
                storePassword = "aimbot123456"
                keyAlias = "aimbot"
                keyPassword = "aimbot123456"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
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
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Infer端专属：推理引擎
    "inferImplementation"(libs.onnxruntime.android)
    "inferImplementation"(libs.tensorflow.lite) {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-support-api")
    }
    "inferImplementation"(libs.tensorflow.lite.gpu)

    // 共享基础依赖（所有变体都包含）
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
