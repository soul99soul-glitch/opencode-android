package com.opencode.android.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/* =============================================================================
 * 外观偏好持久化 —— 主题(亮/暗)+ 强调色,用 DataStore Preferences。
 * 切换即时生效、重启不丢。连接信息(host/port/...)同理另起一个 key 即可。
 * ========================================================================== */
private val Context.dataStore by preferencesDataStore(name = "oc_appearance")

object AppearanceKeys {
    val DARK = booleanPreferencesKey("dark_theme")
    val ACCENT = intPreferencesKey("accent_index") // 对应 OcAccent.entries 下标
}

class AppearanceRepository(private val context: Context) {

    val darkTheme: Flow<Boolean> =
        context.dataStore.data.map { it[AppearanceKeys.DARK] ?: false }

    val accentIndex: Flow<Int> =
        context.dataStore.data.map { it[AppearanceKeys.ACCENT] ?: 0 }

    suspend fun setDark(dark: Boolean) {
        context.dataStore.edit { it[AppearanceKeys.DARK] = dark }
    }

    suspend fun setAccentIndex(index: Int) {
        context.dataStore.edit { it[AppearanceKeys.ACCENT] = index }
    }
}
