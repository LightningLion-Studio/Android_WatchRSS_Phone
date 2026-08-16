package com.lightningstudio.watchrss.phone.tips

/**
 * Tip 状态存取接口，使 TipManager 纯 JVM 可测（单测中注入内存实现）。
 */
interface TipStateBackend {
    fun loadAll(): Map<TipId, TipState>
    fun save(tipId: TipId, state: TipState)
    fun clear()
    fun loadDebugShowAll(): Boolean
    fun saveDebugShowAll(enabled: Boolean)
}
