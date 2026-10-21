package com.example.myapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.view.View

/**
 * 老系统（API 17）没有 RenderEffect，ScriptIntrinsicBlur 在低端机上开销也不稳。
 * 这里走"降采样 + 盒式模糊"：
 *  - 把要模糊的条带按 1/scale 画进小位图（小图上模糊几乎免费）
 *  - 横向、纵向各做一次盒式模糊，接近高斯的效果
 *  - ImageView 放大时靠双线性插值补细节
 */
object Blur {

    fun createTarget(view: View, bandHeight: Int, scale: Int): Bitmap {
        val w = maxOf(1, view.width / scale)
        val h = maxOf(1, bandHeight / scale)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    }

    fun captureInto(
        source: View,
        bandTop: Int,
        bandHeight: Int,
        scale: Int,
        radius: Int,
        target: Bitmap
    ) {
        if (target.isRecycled) return

        val canvas = Canvas(target)
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        canvas.save()
        canvas.scale(1f / scale, 1f / scale)
        canvas.translate(0f, -bandTop.toFloat())
        source.draw(canvas)
        canvas.restore()

        blurBitmap(target, radius)
    }

    /**
     * 背景图模糊：先缩到 maxSide 以内再糊，返回小图交给 ImageView 拉伸。
     * 4.2 上对整屏图直接做卷积会卡死，缩到 360px 宽后开销可以忽略。
     */
    fun blurForDisplay(src: Bitmap, amount: Int, maxSide: Int = 360): Bitmap {
        if (amount <= 0) return src
        val scale = maxOf(1, maxOf(src.width, src.height) / maxSide)
        val w = maxOf(1, src.width / scale)
        val h = maxOf(1, src.height / scale)
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        // createScaledBitmap 返回的是不可变位图，setPixels 会抛 IllegalStateException
        val small = if (scaled.isMutable) scaled else scaled.copy(Bitmap.Config.ARGB_8888, true)
        blurBitmap(small, maxOf(1, amount / 4))
        return small
    }

    private fun blurBitmap(bmp: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bmp.width
        val h = bmp.height
        var pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // 两个方向各跑两遍，方波叠加后接近高斯
        repeat(2) {
            pixels = boxPass(pixels, w, h, radius, horizontal = true)
            pixels = boxPass(pixels, w, h, radius, horizontal = false)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun boxPass(src: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean): IntArray {
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (d in -radius..radius) {
                    val xx = if (horizontal) x + d else x
                    val yy = if (horizontal) y else y + d
                    if (xx < 0 || xx >= w || yy < 0 || yy >= h) continue
                    val c = src[yy * w + xx]
                    a += (c ushr 24) and 0xff
                    r += (c ushr 16) and 0xff
                    g += (c ushr 8) and 0xff
                    b += c and 0xff
                    n++
                }
                if (n == 0) continue
                out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }
}

