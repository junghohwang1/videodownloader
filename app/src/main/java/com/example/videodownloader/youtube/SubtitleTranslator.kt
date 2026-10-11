package com.example.videodownloader.youtube

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 자막을 기기 안에서 한국어로 번역한다(Google ML Kit). 언어마다 처음 한 번 번역 모델(약 30MB)을 받는다.
 * 지원하지 않는 언어면 null.
 */
object SubtitleTranslator {

    suspend fun toKorean(subtitles: FetchedSubtitles): List<Cue>? {
        val source = TranslateLanguage.fromLanguageTag(subtitles.languageTag.substringBefore('-')) ?: return null
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(TranslateLanguage.KOREAN)
            .build()
        val translator = Translation.getClient(options)
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            // 같은 문장이 여러 번 나오는 경우(후렴 등)는 한 번만 번역한다.
            val translated = HashMap<String, String>()
            return subtitles.cues.map { cue ->
                val text = translated.getOrPut(cue.text) { translator.translate(cue.text).await() }
                cue.copy(text = text)
            }
        } finally {
            translator.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
