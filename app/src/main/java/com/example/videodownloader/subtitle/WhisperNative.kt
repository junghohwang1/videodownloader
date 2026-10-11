package com.example.videodownloader.subtitle

/**
 * whisper.cpp JNI(app/src/main/cpp/whisper_jni.cpp). 한 번에 하나의 인식만 돌린다.
 * 진행률(0..100)과 취소는 전역 값이라 동시에 두 개를 돌리면 섞인다.
 */
object WhisperNative {
    init {
        System.loadLibrary("vdwhisper")
    }

    /** 모델을 불러온다. 실패하면 0. */
    external fun init(modelPath: String): Long

    external fun free(ctx: Long)

    /** 16kHz 모노 float 음성을 인식한다. 0 이면 성공, -100 이면 취소. */
    external fun transcribe(ctx: Long, audio: FloatArray, threads: Int, language: String): Int

    /** 진행 중인 [transcribe] 를 멈춘다(다른 스레드에서 불러도 된다). */
    external fun cancel()

    /** 진행 중인 [transcribe] 의 진행률(0..100). */
    external fun progress(): Int

    external fun segmentCount(ctx: Long): Int

    external fun segmentText(ctx: Long, index: Int): ByteArray

    /** 1/100초 단위. */
    external fun segmentStart(ctx: Long, index: Int): Long

    external fun segmentEnd(ctx: Long, index: Int): Long

    external fun language(ctx: Long): String
}
