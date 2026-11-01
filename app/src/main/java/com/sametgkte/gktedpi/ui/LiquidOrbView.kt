package com.sametgkte.gktedpi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import kotlin.math.min

/**
 * 3D Optical Liquid Glass Sphere Power Control with refractive dome highlight & live accent aura.
 */
class LiquidOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val domePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val arcRect = RectF()
    private val clipPath = Path()
    private val personalizationListener: () -> Unit = { invalidate() }

    var isConnectedState: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) pulseAnimator.start() else {
                    pulseAnimator.cancel()
                    pulseFraction = 0f
                }
                invalidate()
            }
        }

    private var pulseFraction = 0f
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulseFraction = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PersonalizationManager.addListener(personalizationListener)
    }

    override fun onDetachedFromWindow() {
        PersonalizationManager.removeListener(personalizationListener)
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (PersonalizationManager.getConfig(context).hapticEnabled) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(110L).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(260L)
                    .setInterpolator(OvershootInterpolator(2.1f))
                    .start()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        if (maxR <= 8f) return

        val cfg = PersonalizationManager.getConfig(context)
        val accent = cfg.accent
        val isLight = cfg.isLightResolved(context)
        val dp = resources.displayMetrics.density
        val orbRadius = maxR * 0.68f

        // 1. Liquid Aura Glow
        val haloRadius = maxR * (0.90f + 0.08f * pulseFraction)
        val auraAlpha = if (isConnectedState) 140 else 65
        haloPaint.shader = RadialGradient(
            cx, cy, haloRadius,
            intArrayOf(withAlpha(accent.primaryHex, auraAlpha), Color.TRANSPARENT),
            floatArrayOf(0.22f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, haloRadius, haloPaint)

        // 2. Refractive Outer Orbit Ring
        ringPaint.strokeWidth = 1.4f * dp
        ringPaint.color = if (isConnectedState) {
            withAlpha(accent.glowHex, 220)
        } else {
            if (isLight) Color.argb(120, 100, 116, 139) else Color.argb(115, 255, 255, 255)
        }
        canvas.drawCircle(cx, cy, orbRadius + 9f * dp + (3.5f * dp * pulseFraction), ringPaint)

        // 3. 3D Liquid Glass Sphere Body
        clipPath.reset()
        clipPath.addCircle(cx, cy, orbRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        bodyPaint.shader = RadialGradient(
            cx - orbRadius * 0.30f,
            cy - orbRadius * 0.32f,
            orbRadius * 1.45f,
            if (isConnectedState) {
                intArrayOf(
                    withAlpha(accent.glowHex, 235),
                    withAlpha(accent.primaryHex, 215),
                    withAlpha(accent.secondaryHex, 235),
                )
            } else {
                intArrayOf(
                    withAlpha(accent.primaryHex, 155),
                    if (isLight) Color.argb(195, 30, 41, 59) else Color.argb(185, 18, 26, 38),
                    Color.argb(225, 8, 13, 22),
                )
            },
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, orbRadius, bodyPaint)

        // Convex Top Glass Crescent Reflection
        domePaint.shader = LinearGradient(
            cx, cy - orbRadius,
            cx, cy,
            intArrayOf(
                Color.argb(145, 255, 255, 255),
                Color.argb(28, 255, 255, 255),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawOval(
            cx - orbRadius * 0.76f,
            cy - orbRadius * 0.92f,
            cx + orbRadius * 0.76f,
            cy - orbRadius * 0.06f,
            domePaint
        )
        canvas.restore()

        // 4. Specular Fresnel Sphere Rim
        borderPaint.strokeWidth = 2.0f * dp
        borderPaint.shader = LinearGradient(
            cx - orbRadius, cy - orbRadius,
            cx + orbRadius, cy + orbRadius,
            intArrayOf(
                Color.WHITE,
                Color.argb(120, 255, 255, 255),
                withAlpha(accent.glowHex, 235),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, orbRadius - dp, borderPaint)

        // 5. Crisp Power Glyph
        val iconRadius = orbRadius * 0.27f
        iconPaint.strokeWidth = 3.5f * dp
        iconPaint.color = Color.WHITE
        arcRect.set(cx - iconRadius, cy - iconRadius, cx + iconRadius, cy + iconRadius)
        canvas.drawArc(arcRect, -52f, 284f, false, iconPaint)
        canvas.drawLine(cx, cy - iconRadius * 1.18f, cx, cy - iconRadius * 0.12f, iconPaint)
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
}
