plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qalabox.emu"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qalabox.emu"
        minSdk = 29          // أندرويد 10+ كما تم الاتفاق
        // targetSdk 28 جوهري لعمل المحاكي (راجع docs/FIXES.md #35):
        // أندرويد 10+ يمنع (W^X) التطبيقات ذات targetSdk ≥ 29 من تنفيذ أي ملف
        // من مجلد بياناتها (SELinux) — فيتعذر تشغيل proot/box64 إطلاقاً.
        // targetSdk 28 يبقي التطبيق في نطاق SELinux القديم untrusted_app_27
        // الذي يسمح بالتنفيذ — وهو النمط المعتمد لدى Termux/Winlator/Mobox.
        targetSdk = 28
        versionCode = 3
        versionName = "1.1.1"
        ndkVersion = "26.1.10909125"
        ndk {
            // محاكي للأجهزة الحقيقية ARM64 (أغلب هواتف أندرويد الحديثة)
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // مكتبات وقت التشغيل تأتي من حزمة runtime وليست من jniLibs
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
