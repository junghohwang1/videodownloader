// whisper.cpp 를 Kotlin(WhisperNative)에서 부르기 위한 JNI 연결부.
// 진행률·취소는 JNI 콜백 대신 전역 원자 변수로 주고받는다(ggml 작업 스레드에서 JNI 를 부르면 안 된다).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "whisper.h"

#define TAG "vdwhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// 동시에 여러 조각을 인식할 수 있도록 진행률은 작업 칸(slot)마다 따로 둔다. 취소는 모든 칸에 함께 적용된다.
static const int MAX_SLOTS = 4;
static std::atomic<bool> g_cancel{false};
static std::atomic<int> g_progress[MAX_SLOTS];

static whisper_context *ctx_of(jlong ptr) { return reinterpret_cast<whisper_context *>(ptr); }

static std::atomic<bool> g_backends_loaded{false};

extern "C" {

/** 앱 네이티브 라이브러리 폴더에서 CPU 계산부(libggml-cpu-*.so)를 불러 이 휴대폰에 가장 빠른 것을 쓰게 한다. */
JNIEXPORT void JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_loadBackends(JNIEnv *env, jobject, jstring dir) {
    if (g_backends_loaded.exchange(true)) return;
    const char *path = env->GetStringUTFChars(dir, nullptr);
    ggml_backend_load_all_from_path(path);
    env->ReleaseStringUTFChars(dir, path);
    LOGI("ggml backends loaded, devices=%zu", ggml_backend_dev_count());
}

JNIEXPORT jlong JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_init(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    // CPU 에서도 어텐션 계산이 빨라진다.
    params.flash_attn = true;
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

/** 자막 만들기를 시작할 때 한 번 부른다(조각마다 부르면 다른 칸에 건 취소가 풀린다). */
JNIEXPORT void JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_resetCancel(JNIEnv *, jobject) {
    g_cancel.store(false);
}

JNIEXPORT jint JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_progress(JNIEnv *, jobject, jint slot) {
    return slot >= 0 && slot < MAX_SLOTS ? g_progress[slot].load() : 0;
}

/** 0 이면 성공. language 는 "auto" 면 자동 감지. */
JNIEXPORT jint JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong ptr, jfloatArray audio, jint threads, jstring language, jint slot) {
    const int s = slot >= 0 && slot < MAX_SLOTS ? slot : 0;
    g_progress[s].store(0);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string lang_copy(lang);
    env->ReleaseStringUTFChars(language, lang);



    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    params.language = lang_copy.c_str();
    params.translate = false;
    // 앞 문장을 다음 구간의 힌트로 넘기지 않는다. 넘기면 같은 문장이 되풀이되는 일이 잦다(특히 무음 건너뛰기와 함께).
    params.n_max_text_ctx = 0;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.progress_callback = [](whisper_context *, whisper_state *, int progress, void *user) {
        g_progress[reinterpret_cast<intptr_t>(user)].store(progress);
    };
    params.progress_callback_user_data = reinterpret_cast<void *>(static_cast<intptr_t>(s));
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

/** 말소리 구간 감지(VAD) 모델을 불러온다. 실패하면 0. */
JNIEXPORT jlong JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_vadInit(JNIEnv *env, jobject, jstring model_path, jint threads) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = threads;
    params.use_gpu = false;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(vctx);
}

/** 16kHz 모노 음성에서 말소리 구간을 찾아 [시작, 끝, 시작, 끝 …](초)로 돌려준다. */
JNIEXPORT jfloatArray JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_vadSegments(JNIEnv *env, jobject, jlong ptr, jfloatArray audio) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(ptr);
    const jsize length = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    whisper_vad_segments *segments = whisper_vad_segments_from_samples(vctx, whisper_vad_default_params(), samples, length);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);
    const int n = segments ? whisper_vad_segments_n_segments(segments) : 0;
    jfloatArray out = env->NewFloatArray(n * 2);
    if (n > 0) {
        std::vector<jfloat> times(n * 2);
        for (int i = 0; i < n; i++) {
            // 결과는 1/100초 단위
            times[i * 2] = whisper_vad_segments_get_segment_t0(segments, i) / 100.0f;
            times[i * 2 + 1] = whisper_vad_segments_get_segment_t1(segments, i) / 100.0f;
        }
        env->SetFloatArrayRegion(out, 0, n * 2, times.data());
    }
    if (segments) whisper_vad_free_segments(segments);
    return out;
}

JNIEXPORT void JNICALL
Java_com_example_videodownloader_subtitle_WhisperNative_vadFree(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_vad_free(reinterpret_cast<whisper_vad_context *>(ptr));
}

} // extern "C"
