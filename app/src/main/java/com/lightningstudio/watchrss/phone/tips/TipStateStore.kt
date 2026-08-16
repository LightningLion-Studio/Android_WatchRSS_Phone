package com.lightningstudio.watchrss.phone.tips

import android.content.Context

/**
 * Tip 状态与调试开关的 SharedPreferences 实现。
 * 非机密数据，普通 SharedPreferences 即可；序列化逻辑在 [TipCodecs]。
 */
class TipStateStore(context: Context) : TipStateBackend {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun loadAll(): Map<TipId, TipState> =
        preferences.getString(KEY_STATES, null)?.let { raw ->
            TipCodecs.tipStatesFromJson(raw)
                .also { if (it == null) preferences.edit().remove(KEY_STATES).apply() }
        } ?: emptyMap()

    override fun save(tipId: TipId, state: TipState) {
        val states = loadAll().toMutableMap()
        states[tipId] = state
        preferences.edit()
            .putString(KEY_STATES, TipCodecs.tipStatesToJson(states).toString())
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_STATES).remove(KEY_DEBUG_SHOW_ALL).apply()
    }

    override fun loadDebugShowAll(): Boolean =
        preferences.getBoolean(KEY_DEBUG_SHOW_ALL, false)

    override fun saveDebugShowAll(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DEBUG_SHOW_ALL, enabled).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "watchrss_tips_state"
        private const val KEY_STATES = "states"
        private const val KEY_DEBUG_SHOW_ALL = "debugShowAll"
    }
}
