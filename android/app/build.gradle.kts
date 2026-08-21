plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "id.sekolah.pengunci_ujian"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "id.sekolah.pengunci_ujian"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // WAJIB samakan dengan --target-platform saat build.
        // Tanpa ini, plugin (ML Kit barcode, datastore, CameraX) menyelipkan
        // .so untuk ABI yang tidak kita build. HP 32-bit lalu memilih
        // armeabi-v7a sebagai ABI utama, berhasil install, lalu CRASH saat
        // dibuka karena libflutter.so/libapp.so tidak ada di ABI itu.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // ndk.abiFilters tidak selalu menyaring .so bawaan AAR pihak ketiga,
    // jadi ABI yang tidak kita build dibuang lagi saat packaging.
    packaging {
        jniLibs {
            excludes += setOf("lib/x86/**", "lib/x86_64/**")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

flutter {
    source = "../.."
}
