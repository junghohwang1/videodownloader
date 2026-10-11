package com.example.videodownloader.subtitle

/**
 * whisper.cpp JNI(app/src/main/cpp/whisper_jni.cpp). 한 번에 하나의 인식만 돌린다.
 * 진행률(0..100)과 취소는 전역 값이라 동시에 두 개를 돌리면 섞인다.
 */
object WhisperNative {
    init {
        System.loadLibrary("vdwhisper")
    }

    /** 앱 네이티브 라이브러리 폴더([dir])의 CPU 계산부를 불러온다. [init] 전에 한 번 부른다. */
    external fun loadBackends(dir: String)

    /** 모델을 불러온다. 실패하면 0. */
    external fun init(modelPath: String): Long

    external fun free(ctx: Long)

    /** 16kHz 모노 float 음성을 인식한다. 0 이면 성공, -100 이면 취소. [slot] 은 동시에 돌릴 때 진행률을 나눠 볼 작업 칸(0..3). */
    external fun transcribe(ctx: Long, audio: FloatArray, threads: Int, language: String, slot: Int): Int

    /** 말소리 구간 감지(VAD) 모델을 불러온다. 실패하면 0. */
    external fun vadInit(modelPath: String, threads: Int): Long

    /** 16kHz 모노 음성에서 말소리 구간을 찾는다. [시작, 끝, 시작, 끝 …](초). */
    external fun vadSegments(vad: Long, audio: FloatArray): FloatArray

    external fun vadFree(vad: Long)

    /** 진행 중인 [transcribe] 를 모두 멈춘다(다른 스레드에서 불러도 된다). */
    external fun cancel()

    /** 자막 만들기를 시작할 때 한 번 취소 표시를 지운다. */
    external fun resetCancel()

    /** [slot] 칸에서 진행 중인 [transcribe] 의 진행률(0..100). */
    external fun progress(slot: Int): Int

    external fun segmentCount(ctx: Long): Int

    external fun segmentText(ctx: Long, index: Int): ByteArray

    /** 1/100초 단위. */
    external fun segmentStart(ctx: Long, index: Int): Long

    external fun segmentEnd(ctx: Long, index: Int): Long

    external fun language(ctx: Long): String
}
