package io.github.beacon.app.widget

import org.json.JSONObject
import kotlin.math.min

/** 文字列表的列数、行数与自适应字号。预览和桌面共用这一套规则。 */
object WidgetListLayout {
    /** 自动字号按从大到小尝试，取第一个能让全部可见条目完整放下的。 */
    val sizes = floatArrayOf(16f, 14f, 12f)

    /** 双列时每列至少要放得下六个最小号汉字，否则挤成一条竖线，不如单列。 */
    private const val MIN_COLUMN_CHARS = 6f

    /** [fits] 表示当前可见条目在该字号下全部放得下，渲染端据此决定是否退回单列。 */
    data class Plan(val textSize: Float, val perColumn: Int, val limit: Int, val fits: Boolean)

    /** 文字列表的字号默认自动（0）；显式选过号的沿用用户选择，其他模板仍是 16 号。 */
    fun preferredSize(config: JSONObject): Int = when {
        config.has("text_size") -> config.optInt("text_size", 16)
        config.optString("template") == "text_list" -> 0
        else -> 16
    }

    /** [configured] 为 0 时跟随尺寸：宽卡双列、方卡单列；1、2 为用户显式选择。 */
    fun columns(configured: Int, wide: Boolean, width: Float, pad: Float, scale: Float): Int {
        val preferred = when (configured) { 1 -> 1; 2 -> 2; else -> if (wide) 2 else 1 }
        if (preferred < 2) return 1
        val perColumn = (width - 2 * pad) / 2 - 8 * scale
        return if (perColumn < MIN_COLUMN_CHARS * 12f * scale) 1 else 2
    }

    fun perColumn(contentHeight: Float, textSize: Float, scale: Float, lines: Int): Int =
        (contentHeight / (textSize * scale * 1.6f * lines.coerceIn(1, 2))).toInt().coerceAtLeast(1)

    /**
     * [fixedSize] 大于 0 时沿用用户选定的字号；等于 0 表示自动，按 [measure] 实测宽度挑字号。
     * [measure] 收到条目下标而不是文字，这样调用方能把右对齐后缀的宽度一起算进去。
     * 都放不下时退回最小字号并置 `fits=false`，由调用方决定换成单列或交给渲染端省略。
     */
    fun plan(
        count: Int, requested: Int, columns: Int, lines: Int,
        contentHeight: Float, usableWidth: Float, fixedSize: Float, scale: Float,
        measure: (Int, Float) -> Float,
    ): Plan {
        fun at(size: Float, fits: Boolean = true): Plan {
            val per = perColumn(contentHeight, size, scale, lines)
            return Plan(size, per, min(count, min(per * columns, requested.coerceAtLeast(0))), fits)
        }
        if (fixedSize > 0f) return at(fixedSize)
        return sizes.map(::at).firstOrNull { plan ->
            (0 until plan.limit).all { measure(it, plan.textSize) <= usableWidth }
        } ?: at(sizes.last(), fits = false)
    }
}
