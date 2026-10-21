package com.example.myapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.sin

/**
 * 明暗切换：太阳 / 月亮 双端胶囊，蓝色滑块指示当前状态。
 * 自绘，不依赖图标资源；API 14 上也没有 VectorDrawable 的顾虑。
 */
class ThemeToggleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pill = RectF()
    

    private val colorPrimary = ContextCompat.getColor(context, R.color.primary)
    private val colorSurface = ContextCompat.getColor(context, R.color.surface)
    private val colorDivider = ContextCompat.getColor(context, R.color.divider)
    private val colorInactive = ContextCompat.getColor(context, R.color.text_secondary)
    private val colorSun = 0xFFF59E0B.toInt()

    /** 0 = 太阳（浅色），1 = 月亮（深色） */
    private var progress = 0f
    private var animator: ValueAnimator? = null

    var dark: Boolean = false
        set(value) {
            field = value
            if (animator?.isRunning != true) {
                progress = if (value) 1f else 0f
                invalidate()
            }
        }

    fun animateTo(targetDark: Boolean, onEnd: (() -> Unit)? = null) {
        animator?.cancel()
        val target = if (targetDark) 1f else 0f
        val a = ValueAnimator.ofFloat(progress, target)
        a.duration = 220
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                dark = targetDark
                onEnd?.invoke()
            }
        })
        animator = a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        val vInset = 3f * d
        val hInset = 2f * d
        val radius = (height - 2 * vInset) / 2f
        pill.set(hInset, vInset, width - hInset, height - vInset)

        paint.style = Paint.Style.FILL
        paint.color = ContextCompat.getColor(context, R.color.bg)
        canvas.drawRoundRect(pill, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * d
        paint.color = colorDivider
        canvas.drawRoundRect(pill, radius, radius, paint)

        val leftCx = pill.left + radius
        val rightCx = pill.right - radius
        val cy = pill.centerY()
        val iconR = radius * 0.62f

        // 滑块
        val thumbCx = leftCx + (rightCx - leftCx) * progress
        paint.style = Paint.Style.FILL
        paint.color = colorPrimary
        canvas.drawCircle(thumbCx, cy, radius * 0.84f, paint)

        drawSun(canvas, leftCx, cy, iconR, active = progress < 0.5f)
        drawMoon(canvas, rightCx, cy, iconR, active = progress >= 0.5f)
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, active: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (active) colorSun else colorInactive
        canvas.drawCircle(cx, cy, r * 0.46f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.16f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (active) colorSun else colorInactive
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val cs = cos(angle).toFloat()
            val sn = sin(angle).toFloat()
            canvas.drawLine(
                cx + r * 0.68f * cs, cy + r * 0.68f * sn,
                cx + r * 0.92f * cs, cy + r * 0.92f * sn,
                paint
            )
        }
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float, active: Boolean) {
        // 两个圆做 even-odd 布尔，交点以内被挖掉，得到月牙
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        path.addCircle(cx, cy, r * 0.62f, Path.Direction.CW)
        path.addCircle(cx + r * 0.30f, cy - r * 0.14f, r * 0.47f, Path.Direction.CW)

        paint.style = Paint.Style.FILL
        paint.color = if (active) Color.WHITE else colorInactive
        canvas.drawPath(path, paint)
    }
}


