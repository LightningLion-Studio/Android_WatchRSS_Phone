package com.lightningstudio.watchrss.phone.tips

/**
 * 提示目录：新增一条提示只需在这里追加 [TipDefinition]，
 * 并在对应控件上挂 Modifier.tipAnchor(tip.id)，无需改动任何框架逻辑。
 */
object TipCatalog {

    val all: List<TipDefinition> = emptyList()

    fun byId(id: TipId): TipDefinition? = all.firstOrNull { it.id == id }
}
