package com.tvbridge.app

import android.graphics.Path
import android.graphics.RectF

/**
 * Rounded-rect path built from arcs only: Path.addRoundRect and Canvas.drawRoundRect are API 21+.
 *
 * 从源工程 AppTileView.kt 抽取的最小工具函数；AppIcons.Vector.box() 依赖它，
 * 而 AppTileView 本身属于桌面专用类，不纳入直播 APK。
 */
internal fun roundedRectPath(l: Float, t: Float, r: Float, b: Float, radius: Float): Path {
    val rr = minOf(radius, (r - l) / 2f, (b - t) / 2f).coerceAtLeast(0f)
    val p = Path()
    p.moveTo(l + rr, t)
    p.lineTo(r - rr, t)
    p.arcTo(RectF(r - 2 * rr, t, r, t + 2 * rr), -90f, 90f)
    p.lineTo(r, b - rr)
    p.arcTo(RectF(r - 2 * rr, b - 2 * rr, r, b), 0f, 90f)
    p.lineTo(l + rr, b)
    p.arcTo(RectF(l, b - 2 * rr, l + 2 * rr, b), 90f, 90f)
    p.lineTo(l, t + rr)
    p.arcTo(RectF(l, t, l + 2 * rr, t + 2 * rr), 180f, 90f)
    p.close()
    return p
}
