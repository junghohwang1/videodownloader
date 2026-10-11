package com.example.videodownloader.subtitle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 자막을 한국어로 번역한다(구글 번역 웹 주소 — 공식 API 가 아니라 막히면 원래 언어 자막을 쓴다).
 * 요청 수를 줄이려고 여러 줄을 줄바꿈으로 묶어 보내고, 돌아온 번역을 다시 줄 단위로 나눈다.
 */
class SubtitleTranslator(private val client: OkHttpClient) {

    suspend fun toKorean(cues: List<Cue>, sourceLanguage: String, onProgress: (Float) -> Unit = {}): List<Cue> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<Cue>(cues.size)
            val batches = batches(cues)
            batches.forEachIndexed { i, batch ->
                val lines = batch.map { it.text.replace('\n', ' ') }
                val translated = translate(lines.joinToString("\n"), sourceLanguage).split('\n').map { it.trim() }
                if (translated.size == lines.size) {
                    batch.zip(translated).forEach { (cue, text) -> out += cue.copy(text = text.ifBlank { cue.text }) }
                } else {
                    // 줄 수가 어긋나면 이 묶음은 한 줄씩 번역한다.
                    batch.forEach { cue -> out += cue.copy(text = translate(cue.text, sourceLanguage).trim().ifBlank { cue.text }) }
                }
                onProgress((i + 1).toFloat() / batches.size)
            }
            out
        }

    private fun batches(cues: List<Cue>): List<List<Cue>> {
        val result = mutableListOf<List<Cue>>()
        var current = mutableListOf<Cue>()
        var length = 0
        for (cue in cues) {
            if (current.isNotEmpty() && length + cue.text.length > MAX_CHARS) {
                result += current
                current = mutableListOf()
                length = 0
            }
            current += cue
            length += cue.text.length + 1
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private fun translate(text: String, sourceLanguage: String): String {
        val url = "https://translate.googleapis.com/translate_a/single".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("sl", sourceLanguage.ifBlank { "auto" })
            .addQueryParameter("tl", "ko")
            .addQueryParameter("dt", "t")
            .build()
        // 긴 글은 주소 길이 제한을 넘지 않도록 본문(q)으로 보낸다.
        val body = okhttp3.FormBody.Builder().add("q", text).build()
        val response = client.newCall(Request.Builder().url(url).post(body).build()).execute()
        val json = response.use {
            if (!it.isSuccessful) throw IOException("번역 서버 응답 오류 (HTTP ${it.code})")
            it.body.string()
        }
        val sentences = Json.parseToJsonElement(json).jsonArray.firstOrNull() as? JsonArray ?: return text
        return sentences.joinToString("") { (it as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content.orEmpty() }
    }

    private companion object {
        const val MAX_CHARS = 3_000
    }
}
