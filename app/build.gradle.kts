import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.videodownloader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.videodownloader"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.0"

        // 자막 만들기(whisper.cpp)용 네이티브 라이브러리. 휴대폰(arm64)과 에뮬레이터(x86_64)만 넣는다.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        // 배포용 고정 서명키(지금 설치본과 동일). 재설치 없이 업데이트되도록 debug·release 모두 이 키로 서명.
        // 공개 저장소에는 올리지 않는다(.gitignore). 키가 없는 PC 에서는 기본 debug 키로 서명된다.
        val fixedKey = file("signing/appkey.jks")
        if (fixedKey.exists()) {
            getByName("debug") {
                storeFile = fixedKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // debug 와 같은 고정 서명키로 서명해야 기존 설치본 위에 업데이트된다.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe Extractor 가 최신 Java API 를 쓰므로 desugaring 이 필요하다.
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer)

    implementation(libs.okhttp)
    // 유튜브 광고 차단 스크립트를 문서 시작 시점에 넣기 위해
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    implementation(libs.newpipe.extractor)
    // 한국어 자막이 없는 유튜브 영상의 자막을 기기 안에서 한국어로 번역
    implementation(libs.mlkit.translate)
    coreLibraryDesugaring(libs.android.desugar)

    testImplementation(libs.junit)
}
