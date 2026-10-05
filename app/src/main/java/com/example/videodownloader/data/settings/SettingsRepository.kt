package com.example.videodownloader.data.settings

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class SearchEngine(val label: String, private val template: String) {
    GOOGLE("Google", "https://www.google.com/search?q=%s"),
    NAVER("네이버", "https://m.search.naver.com/search.naver?query=%s"),
    BING("Bing", "https://www.bing.com/search?q=%s"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s");

    fun queryUrl(query: String): String = template.replace("%s", URLEncoder.encode(query, "UTF-8"))
}

data class AppSettings(
    val maxConcurrent: Int = 2,
    val wifiOnly: Boolean = false,
    val notifyOnComplete: Boolean = true,
    val saveHistory: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.GOOGLE,
    val pinSet: Boolean = false,
    /** 웹페이지가 화면 아래에 고정해 둔 메뉴·배너를 숨긴다. */
    val hideFixedBars: Boolean = true,
    /** 외국어 페이지를 열면 번역을 제안한다. */
    val offerTranslation: Boolean = true,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
        val NOTIFY_ON_COMPLETE = booleanPreferencesKey("notify_on_complete")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val SEARCH_ENGINE = stringPreferencesKey("search_engine")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val HIDE_FIXED_BARS = booleanPreferencesKey("hide_fixed_bars")
        val OFFER_TRANSLATION = booleanPreferencesKey("offer_translation")
    }

    private val store get() = context.settingsStore

    val settings: Flow<AppSettings> = store.data
        .catch { emit(emptyPreferences()) }
        .map { p ->
            AppSettings(
                maxConcurrent = p[Keys.MAX_CONCURRENT] ?: 2,
                wifiOnly = p[Keys.WIFI_ONLY] ?: false,
                notifyOnComplete = p[Keys.NOTIFY_ON_COMPLETE] ?: true,
                saveHistory = p[Keys.SAVE_HISTORY] ?: true,
                searchEngine = p[Keys.SEARCH_ENGINE]
                    ?.let { name -> SearchEngine.entries.firstOrNull { it.name == name } }
                    ?: SearchEngine.GOOGLE,
                pinSet = p[Keys.PIN_HASH] != null,
                hideFixedBars = p[Keys.HIDE_FIXED_BARS] ?: true,
                offerTranslation = p[Keys.OFFER_TRANSLATION] ?: true,
            )
        }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setMaxConcurrent(value: Int) {
        store.edit { it[Keys.MAX_CONCURRENT] = value.coerceIn(1, 5) }
    }

    suspend fun setWifiOnly(value: Boolean) {
        store.edit { it[Keys.WIFI_ONLY] = value }
    }

    suspend fun setNotifyOnComplete(value: Boolean) {
        store.edit { it[Keys.NOTIFY_ON_COMPLETE] = value }
    }

    suspend fun setSaveHistory(value: Boolean) {
        store.edit { it[Keys.SAVE_HISTORY] = value }
    }

    suspend fun setHideFixedBars(value: Boolean) {
        store.edit { it[Keys.HIDE_FIXED_BARS] = value }
    }

    suspend fun setOfferTranslation(value: Boolean) {
        store.edit { it[Keys.OFFER_TRANSLATION] = value }
    }

    suspend fun setSearchEngine(value: SearchEngine) {
        store.edit { it[Keys.SEARCH_ENGINE] = value.name }
    }

    suspend fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        store.edit {
            it[Keys.PIN_SALT] = Base64.encodeToString(salt, Base64.NO_WRAP)
            it[Keys.PIN_HASH] = hashPin(pin, salt)
        }
    }

    suspend fun clearPin() {
        store.edit {
            it.remove(Keys.PIN_SALT)
            it.remove(Keys.PIN_HASH)
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val prefs = store.data.first()
        val salt = prefs[Keys.PIN_SALT] ?: return false
        val expected = prefs[Keys.PIN_HASH] ?: return false
        val actual = hashPin(pin, Base64.decode(salt, Base64.NO_WRAP))
        return MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }
}
