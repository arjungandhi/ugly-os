package com.uglyos.common.theme

/**
 * The registry of available themes. Add a new [Theme] here and it becomes
 * pullable by name and listable in a picker — nothing else to wire up.
 */
object Themes {
    /** Used when nothing is selected or a saved name no longer resolves. */
    val Default: Theme = Nord

    /** Every registered theme, in picker order. */
    val all: List<Theme> = listOf(Nord)

    /** Look a theme up by its stable [Theme.name]; null if unknown. */
    fun byName(name: String?): Theme? = all.firstOrNull { it.name == name }

    /** Like [byName], but falls back to [Default] instead of null. */
    fun byNameOrDefault(name: String?): Theme = byName(name) ?: Default
}
