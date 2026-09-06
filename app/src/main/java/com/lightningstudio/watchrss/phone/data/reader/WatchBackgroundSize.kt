package com.lightningstudio.watchrss.phone.data.reader

import kotlin.math.floor

internal data class WatchBackgroundSize(val width: Int, val height: Int)

/** Full source within a 2x display box. Rotation is applied before sizing, never cropped. */
internal fun watchBackgroundSize(
    sourceWidth: Int,
    sourceHeight: Int,
    watchWidth: Int,
    watchHeight: Int,
    rotation: Int = 0,
    video: Boolean = false
): WatchBackgroundSize {
    require(sourceWidth > 0 && sourceHeight > 0 && watchWidth > 0 && watchHeight > 0)
    val swap = Math.floorMod(rotation, 180) == 90
    val width = if (swap) sourceHeight else sourceWidth
    val height = if (swap) sourceWidth else sourceHeight
    val scale = minOf(1.0, watchWidth.toDouble() * 2 / width, watchHeight.toDouble() * 2 / height)
    fun dimension(value: Int): Int {
        val result = floor(value * scale).toInt().coerceAtLeast(1)
        if (!video) return result
        require(result >= 2) { "视频尺寸过小，无法编码偶数尺寸" }
        return result / 2 * 2
    }
    return WatchBackgroundSize(dimension(width), dimension(height))
}
