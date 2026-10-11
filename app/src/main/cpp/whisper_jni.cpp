// whisper.cpp 를 Kotlin(WhisperNative)에서 부르기 위한 JNI 연결부.
// 진행률·취소는 JNI 콜백 대신 전역 원자 변수로 주고받는다(ggml 작업 스레드에서 JNI 를 부르면 안 된다).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <string>

#include "whisper.h"

#define TAG "vdwhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::atomic<bool> g_cancel{false};
static std::atomic<int> g_progress{0};

static whisper_context *ctx_of(jlong ptr) { return reinterpret_cast<whisper_context *>(ptr); }

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_init(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_free(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(ctx_of(ptr));
}

JNIEXPORT void JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_cancel(JNIEnv *, jobject) {
    g_cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_progress(JNIEnv *, jobject) {
    return g_progress.load();
}

/** 0 이면 성공. language 는 "auto" 면 자동 감지. */
JNIEXPORT jint JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray audio, jint threads, jstring language) {
    g_cancel.store(false);
    g_progress.store(0);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string lang_copy(lang);
    env->ReleaseStringUTFChars(language, lang);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang_copy.c_str();
    params.translate = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.progress_callback = [](whisper_context *, whisper_state *, int progress, void *) {
        g_progress.store(progress);
    };
    params.abort_callback = [](void *) -> bool { return g_cancel.load(); };

    const jsize length = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const int result = whisper_full(ctx_of(ptr), params, samples, length);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (g_cancel.load()) return -100;
    return result;
}

JNIEXPORT jint JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_segmentCount(JNIEnv *, jobject, jlong ptr) {
    return whisper_full_n_segments(ctx_of(ptr));
}

/** 토큰 경계에서 UTF-8 이 잘릴 수 있어 문자열 대신 바이트로 넘기고 Kotlin 에서 디코딩한다. */
JNIEXPORT jbyteArray JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_segmentText(JNIEnv *env, jobject, jlong ptr, jint index) {
    const char *text = whisper_full_get_segment_text(ctx_of(ptr), index);
    const auto size = static_cast<jsize>(text ? std::strlen(text) : 0);
    jbyteArray bytes = env->NewByteArray(size);
    if (size > 0) env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(text));
    return bytes;
}

/** 구간 시작·끝(1/100초). */
JNIEXPORT jlong JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_segmentStart(JNIEnv *, jobject, jlong ptr, jint index) {
    return whisper_full_get_segment_t0(ctx_of(ptr), index);
}

JNIEXPORT jlong JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_segmentEnd(JNIEnv *, jobject, jlong ptr, jint index) {
    return whisper_full_get_segment_t1(ctx_of(ptr), index);
}

/** 마지막 인식에서 감지(또는 지정)된 언어 코드(ko, en …). */
JNIEXPORT jstring JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_language(JNIEnv *env, jobject, jlong ptr) {
    const char *lang = whisper_lang_str(whisper_full_lang_id(ctx_of(ptr)));
    return env->NewStringUTF(lang ? lang : "");
}

} // extern "C"
