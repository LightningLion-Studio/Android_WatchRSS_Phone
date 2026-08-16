package com.lightningstudio.watchrss.phone.tips

/**
 * 用户行为事件计数存取接口，使 TipManager 纯 JVM 可测。
 */
interface TipEventBackend {
    /** 生命周期事件计数快照。 */
    fun snapshot(): Map<String, Long>
    fun increment(event: String)
    fun clear()
}
