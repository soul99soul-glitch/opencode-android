package com.opencode.android.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

/* =============================================================================
 * 外观偏好持久化 —— 主题(亮/暗)+ 强调色+ 语言,用 DataStore Preferences。
 * 切换即时生效、重启不丢。连接信息(host/port/...)同理另起一个 key 即可。
 * ========================================================================== */
private val Context.dataStore by preferencesDataStore(name = "oc_appearance")

object AppearanceKeys {
    val DARK = booleanPreferencesKey("dark_theme")
    val ACCENT = intPreferencesKey("accent_index") // 对应 OcAccent.entries 下标
    val LANGUAGE = stringPreferencesKey("language_tag") // "en", "zh-CN", or "" (system default)
}

class AppearanceRepository(context: Context) {

    private val appContext = context.applicationContext

    val darkTheme: Flow<Boolean> =
        appContext.dataStore.data.map { it[AppearanceKeys.DARK] ?: false }

    val accentIndex: Flow<Int> =
        appContext.dataStore.data.map { it[AppearanceKeys.ACCENT] ?: 0 }

    /** Language tag flow: "en", "zh-CN", or empty for system default. */
    val languageTag: Flow<String> =
        appContext.dataStore.data.map { it[AppearanceKeys.LANGUAGE] ?: "" }

    suspend fun setDark(dark: Boolean) {
        appContext.dataStore.edit { it[AppearanceKeys.DARK] = dark }
    }

    suspend fun setAccentIndex(index: Int) {
        appContext.dataStore.edit { it[AppearanceKeys.ACCENT] = index }
    }

    suspend fun setLanguageTag(tag: String) {
        appContext.dataStore.edit { it[AppearanceKeys.LANGUAGE] = tag }
        // Cache to SharedPreferences for fast cold-start access (avoids runBlocking on DataStore)
        appContext.getSharedPreferences("oc_appearance_cache", Context.MODE_PRIVATE)
            .edit().putString("language_tag", tag).apply()
    }

    /** Read the stored language tag once (blocking). */
    suspend fun getLanguageTagOnce(): String = languageTag.first()

    companion object {
        /** Convert a stored language tag to a Locale. */
        fun tagToLocale(tag: String): Locale = when {
            tag.isBlank() -> Locale.getDefault()
            else -> Locale.forLanguageTag(tag)
        }

        /** Available language options: tag → display name. */
        val LANGUAGE_OPTIONS = listOf(
            "" to "System",      // System default
            "en" to "English",
            "zh-CN" to "简体中文",
        )
    }
}
