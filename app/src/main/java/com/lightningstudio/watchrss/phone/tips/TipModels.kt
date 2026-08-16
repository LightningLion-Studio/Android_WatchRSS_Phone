package com.lightningstudio.watchrss.phone.tips

/**
 * 情境提示（TipKit 风格）数据模型。
 * 与 onboarding 引导漏斗完全独立：Tip 由规则和频控驱动，锚定在具体控件上按需出现。
 */
typealias TipId = String

/** 展示频控窗口。ALWAYS 表示无窗口限制（仅受 maxShows 约束）。 */
enum class TipDisplayFrequency(val periodMillis: Long) {
    ALWAYS(0L),
    DAILY(24 * 60 * 60 * 1000L),
    WEEKLY(7 * 24 * 60 * 60 * 1000L)
}

/**
 * 一条提示的完整定义。新增 Tip 只需在 [TipCatalog] 中加一条，不改任何逻辑。
 *
 * @param id 全局唯一，同时是锚点注册键
 * @param priority 同屏多条候选时的优先级，越大越先
 * @param rule 资格规则；null 表示无条件
 * @param maxShows 生命周期内最多展示次数
 * @param invalidateOnEvents 命中这些事件时自动失效（用户已学会该操作）
 */
data class TipDefinition(
    val id: TipId,
    val title: String,
    val message: String,
    val priority: Int = 0,
    val rule: TipRule? = null,
    val displayFrequency: TipDisplayFrequency = TipDisplayFrequency.DAILY,
    val maxShows: Int = Int.MAX_VALUE,
    val invalidateOnEvents: Set<String> = emptySet()
)

/** 每条 Tip 的持久化状态。 */
data class TipState(
    val dismissed: Boolean = false,
    val invalidated: Boolean = false,
    val lastShownAtMillis: Long = 0L,
    val showCount: Int = 0
)

/** 界面状态快照，由各宿主在组合时构建并传入 [TipManager.evaluateEligibleTip]。键缺失视为不满足规则。 */
data class TipParameterValues private constructor(private val values: Map<String, Any>) {

    fun contains(key: String): Boolean = values.containsKey(key)

    fun bool(key: String, default: Boolean = false): Boolean =
        values[key] as? Boolean ?: default

    fun string(key: String, default: String = ""): String =
        values[key] as? String ?: default

    fun long(key: String, default: Long = 0L): Long =
        values[key] as? Long ?: default

    class Builder {
        private val values = mutableMapOf<String, Any>()

        fun put(key: String, value: Boolean) = apply { values[key] = value }
        fun put(key: String, value: String) = apply { values[key] = value }
        fun put(key: String, value: Long) = apply { values[key] = value }

        fun build(): TipParameterValues = TipParameterValues(values.toMap())
    }

    companion object {
        val EMPTY = TipParameterValues(emptyMap())
    }
}

/** 资格规则。对应 TipKit 的参数规则与事件规则。 */
sealed interface TipRule {
    fun evaluate(parameters: TipParameterValues, events: Map<String, Long>): Boolean

    /** 布尔参数规则：参数键存在且值等于 expected 才满足（键缺失一律不满足）。 */
    data class ParamBoolean(val key: String, val expected: Boolean = true) : TipRule {
        override fun evaluate(parameters: TipParameterValues, events: Map<String, Long>): Boolean =
            parameters.contains(key) && parameters.bool(key) == expected
    }

    /** 字符串参数相等规则（键缺失一律不满足）。 */
    data class ParamEquals(val key: String, val value: String) : TipRule {
        override fun evaluate(parameters: TipParameterValues, events: Map<String, Long>): Boolean =
            parameters.contains(key) && parameters.string(key) == value
    }

    /** 事件计数规则：生命周期计数落在 [minCount, maxCount] 区间内才满足。 */
    data class EventCount(val event: String, val minCount: Long = 1L, val maxCount: Long? = null) : TipRule {
        override fun evaluate(parameters: TipParameterValues, events: Map<String, Long>): Boolean {
            val count = events[event] ?: 0L
            return count >= minCount && (maxCount == null || count <= maxCount)
        }
    }

    data class AllOf(val rules: List<TipRule>) : TipRule {
        override fun evaluate(parameters: TipParameterValues, events: Map<String, Long>): Boolean =
            rules.all { it.evaluate(parameters, events) }
    }

    data class AnyOf(val rules: List<TipRule>) : TipRule {
        override fun evaluate(parameters: TipParameterValues, events: Map<String, Long>): Boolean =
            rules.any { it.evaluate(parameters, events) }
    }
}

/** 目录可读性辅助工厂。 */
object TipRules {

    /** 「从未发生过」：事件计数为 0。 */
    fun eventNever(event: String): TipRule =
        TipRule.EventCount(event, minCount = 0L, maxCount = 0L)

    fun eventAtLeast(event: String, count: Long): TipRule =
        TipRule.EventCount(event, minCount = count)

    fun param(key: String, expected: Boolean = true): TipRule =
        TipRule.ParamBoolean(key, expected)

    fun paramEquals(key: String, value: String): TipRule =
        TipRule.ParamEquals(key, value)

    fun allOf(vararg rules: TipRule): TipRule =
        TipRule.AllOf(rules.toList())

    fun anyOf(vararg rules: TipRule): TipRule =
        TipRule.AnyOf(rules.toList())
}
