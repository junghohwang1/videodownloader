// Windows 데스크톱 앱. Android 앱(:app)과 빌드를 분리해 Android SDK 없이도 빌드된다.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // 유튜브 지원(NewPipe Extractor)은 JitPack 으로만 배포된다.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.TeamNewPipe") }
        }
    }
}

rootProject.name = "VideoDownloaderDesktop"
