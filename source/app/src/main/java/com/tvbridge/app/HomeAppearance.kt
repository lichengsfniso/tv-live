package com.tvbridge.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * 主题 shim：直播场景固定 dark + video，壁纸分支不会呈现。
 *
 * 只实现 NativeUi / GlassUi 实际调用的 4 个方法；backdrop 必须返回非 null 纯色 Bitmap，
 * 因为 GlassUi.frost() 不接受 null。原版的壁纸下载 / EXIF 依赖不再需要。
 */
object HomeAppearance {
    private var cached: Bitmap? = null

    fun dark(context: Context): Boolean = true

    fun key(context: Context): String = "com.onetv.live:live:dark"

    fun backdrop(context: Context, w: Int, h: Int, dark: Boolean): Bitmap {
        val b = cached
        if (b != null && b.width == w && b.height == h) return b
        val nb = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        nb.eraseColor(Color.parseColor("#121820"))
        cached = nb
        return nb
    }

    fun drawBackdrop(context: Context, canvas: Canvas, w: Int, h: Int, dark: Boolean, paint: Paint) {
        val c = paint.color
        paint.color = Color.parseColor("#121820")
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.color = c
    }
}
