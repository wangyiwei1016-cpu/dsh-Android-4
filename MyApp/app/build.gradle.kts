import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 发布证书信息放在 keystore.properties（已 gitignore）。
// 文件不存在时 release 不签名，clone 下来的人照样能编译 debug 包。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseSigning = keystoreProps.getProperty("storePassword") != null

android {
    namespace = "com.example.myapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapp"
        // Android 4.0 = API 14，这是整个工程的兼容下限
        minSdk = 14
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Conscrypt 带 native 库，这四类 ABI 都要打进去
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        // minSdk < 21 需要旧式 multidex
        multiDexEnabled = true

        // API 14 没有原生矢量图，交给 AppCompat 的兼容层
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("localDebug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/release.keystore"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias", "dsh")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // API 14 只认 v1 签名
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.multidex:multidex:2.0.1")

    // OkHttp 4.x 要求 API 21，3.12.x 是最后支持 API 14 的版本
    implementation("com.squareup.okhttp3:okhttp:3.12.13")

    // 老系统自带 TLS 只有 1.0/1.1，Conscrypt 补上 TLS 1.2 + 现代加密套件
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
