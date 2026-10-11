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
        // 내장 브라우저(JCEF)가 쓰는 JOGL 은 JogAmp 저장소에만 있다.
        maven("https://jogamp.org/deployment/maven") {
            content { includeGroupByRegex("org\\.jogamp.*") }
        }
    }
}

rootProject.name = "VideoDownloaderDesktop"
