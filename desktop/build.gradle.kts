import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.compose") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    id("org.jetbrains.compose") version "1.12.1"
}

group = "com.example.videodownloader"
version = "1.0.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.example.videodownloader.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "VideoDownloader"
            packageVersion = "1.0.0"
            // MSI(WiX) 는 코드페이지 1252 라 한글을 넣으면 빌드가 실패한다(LGHT0311). 앱 화면은 한글 그대로다.
            description = "Video Downloader"
            vendor = "junghohwang1"
            // NewPipe Extractor(Rhino)·OkHttp 가 쓰는 JDK 모듈
            modules("java.instrument", "java.naming", "java.net.http", "java.sql", "jdk.crypto.ec", "jdk.dynalink", "jdk.unsupported")
            windows {
                menuGroup = "VideoDownloader"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8f0b6c1e-4f8e-4c63-9a0b-6f3d2f7d9b21"
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}
