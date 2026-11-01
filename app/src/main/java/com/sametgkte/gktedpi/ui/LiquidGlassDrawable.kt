package com.sametgkte.gktedpi.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Authentic Optical Liquid Glass Drawable used across EVERY Card, Button, Pill,
 * Input Box, and Container in GkTeDPI.
 */
class LiquidGlassDrawable(
    private val context: Context,
    val kind: Kind = Kind.CARD,
) : Drawable() {

    enum class Kind {
        CARD,
        HERO_ACCENT_CARD,
        ICON_SQUIRCLE,
        HEADER,
        PILL_MUTED,
        PILL_ACTIVE,
        CTA_BUTTON,
        CODE_SURFACE,
    }

    private val rect = RectF()
    private val innerRect = RectF()
    private val clipPath = Path()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val domePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chromaticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var hotspotX = -1f
    private var hotspotY = -1f
    private var isPressedState = false

    override fun setHotspot(x: Float, y: Float) {
        hotspotX = x
        hotspotY = y
        invalidateSelf()
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = state.any { it == android.R.attr.state_pressed }
        if (pressed != isPressedState) {
            isPressedState = pressed
            invalidateSelf()
            return true
        }
        return false
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 2 || b.height() <= 2) return

        val dp = context.resources.displayMetrics.density
        val cfg = PersonalizationManager.getConfig(context)
        val isLight = cfg.isLightResolved(context)
        val accent = cfg.accent

        val pad = 1.1f * dp
        rect.set(b.left + pad, b.top + pad, b.right - pad, b.bottom - pad)
        val w = rect.width()
        val h = rect.height()

        val radius = when (kind) {
            Kind.PILL_MUTED, Kind.PILL_ACTIVE -> h / 2f
            Kind.CTA_BUTTON -> min(h / 2f, (cfg.cornerRadiusDp - 4f) * dp)
            Kind.ICON_SQUIRCLE -> 13f * dp
            Kind.CODE_SURFACE -> 15f * dp
            Kind.HEADER -> cfg.cornerRadiusDp * dp
            Kind.CARD, Kind.HERO_ACCENT_CARD -> cfg.cornerRadiusDp * dp
        }

        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        // Frost alpha multiplier
        val frostStep = cfg.frostLevel // 0, 1, 2
        val baseAlphaBoost = frostStep * 28

        canvas.save()
        canvas.clipPath(clipPath)

        // 1. Translucent Liquid Glass Body Gradient
        when (kind) {
            Kind.CARD, Kind.HEADER -> {
                val colors = when {
                    isLight -> intArrayOf(
                        Color.argb((150 + baseAlphaBoost).coerceAtMost(230), 255, 255, 255),
                        Color.argb((122 + baseAlphaBoost).coerceAtMost(215), 242, 248, 255),
                        Color.argb((140 + baseAlphaBoost).coerceAtMost(225), 232, 242, 252),
                    )
                    cfg.themeMode == PersonalizationManager.ThemeMode.AMOLED_GLASS -> intArrayOf(
                        Color.argb((84 + baseAlphaBoost).coerceAtMost(195), 28, 30, 34),
                        Color.argb((110 + baseAlphaBoost).coerceAtMost(210), 14, 15, 18),
                        Color.argb((128 + baseAlphaBoost).coerceAtMost(220), 8, 9, 11),
                    )
                    else -> intArrayOf(
                        Color.argb((112 + baseAlphaBoost).coerceAtMost(205), 32, 43, 62),
                        Color.argb((132 + baseAlphaBoost).coerceAtMost(215), 16, 23, 36),
                        Color.argb((146 + baseAlphaBoost).coerceAtMost(225), 12, 18, 29),
                    )
                }
                bodyPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    colors, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            }

            Kind.CODE_SURFACE -> {
                val colors = if (isLight) {
                    intArrayOf(Color.argb(175, 235, 242, 250), Color.argb(195, 226, 235, 246))
                } else {
                    intArrayOf(Color.argb(165, 8, 12, 18), Color.argb(190, 4, 7, 12))
                }
                bodyPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.bottom,
                    colors, null, Shader.TileMode.CLAMP
                )
            }

            Kind.ICON_SQUIRCLE -> {
                val c1 = blendWithAlpha(accent.primaryHex, if (isLight) 48 else 62)
                val c2 = blendWithAlpha(accent.secondaryHex, if (isLight) 28 else 36)
                bodyPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(c1, c2), null, Shader.TileMode.CLAMP
                )
            }

            Kind.HERO_ACCENT_CARD -> {
                bodyPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(
                        blendWithAlpha(accent.primaryHex, 210),
                        blendWithAlpha(accent.secondaryHex, 185),
                        blendWithAlpha(accent.secondaryHex, 155),
                    ),
                    floatArrayOf(0f, 0.6f, 1f),
                    Shader.TileMode.CLAMP
                )
            }

            Kind.PILL_MUTED -> {
                val c1 = blendWithAlpha(accent.secondaryHex, if (isLight) 36 else 44)
                val c2 = if (isLight) Color.argb(155, 255, 255, 255) else Color.argb(95, 26, 28, 34)
                bodyPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(c2, c1), null, Shader.TileMode.CLAMP
                )
            }

            Kind.PILL_ACTIVE, Kind.CTA_BUTTON -> {
                val topAlpha = if (kind == Kind.CTA_BUTTON) 225 else 205
                val botAlpha = if (kind == Kind.CTA_BUTTON) 195 else 175
                bodyPaint.shader = LinearGradient(
                    rect.left, rect.top, rect.right, rect.bottom,
                    intArrayOf(
                        blendWithAlpha(accent.primaryHex, topAlpha),
                        blendWithAlpha(accent.secondaryHex, botAlpha),
                        blendWithAlpha(accent.glowHex, 155),
                    ),
                    floatArrayOf(0f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        canvas.drawRect(rect, bodyPaint)

        // 2. Top Convex Dome Specular Reflection (Real Liquid Glass Meniscus Highlight)
        val domeHeight = h * (if (kind == Kind.CARD) 0.36f else 0.50f)
        val domePeakAlpha = when {
            kind == Kind.CTA_BUTTON || kind == Kind.PILL_ACTIVE -> 92
            isLight -> 135
            else -> 58
        }
        domePaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.top + domeHeight,
            intArrayOf(
                Color.argb(domePeakAlpha, 255, 255, 255),
                Color.argb(domePeakAlpha / 4, 255, 255, 255),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + domeHeight, domePaint)

        // 3. Interactive Touch Caustic Glint
        if (isPressedState) {
            val cx = if (hotspotX >= 0f) hotspotX else rect.centerX()
            val cy = if (hotspotY >= 0f) hotspotY else rect.centerY()
            val rad = maxOf(w, h) * 0.65f
            causticPaint.shader = RadialGradient(
                cx, cy, rad,
                intArrayOf(
                    Color.argb(85, 255, 255, 255),
                    blendWithAlpha(accent.glowHex, 48),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, rad, causticPaint)
        }

        canvas.restore()

        // 4. Inner Chromatic Dispersion Bevel (Optical Edge Refraction)
        if (cfg.refractionLevel > 0) {
            val chromaAlpha = if (cfg.refractionLevel == 2) 72 else 38
            chromaticPaint.strokeWidth = (1.6f * dp)
            chromaticPaint.shader = LinearGradient(
                rect.left, rect.top, rect.right, rect.bottom,
                intArrayOf(
                    Color.argb(chromaAlpha, 56, 189, 248),
                    Color.TRANSPARENT,
                    blendWithAlpha(accent.glowHex, chromaAlpha),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            innerRect.set(rect.left + dp, rect.top + dp, rect.right - dp, rect.bottom - dp)
            canvas.drawRoundRect(innerRect, radius - dp, radius - dp, chromaticPaint)
        }

        // 5. Crisp Fresnel Specular Glass Rim
        rimPaint.strokeWidth = when (kind) {
            Kind.PILL_ACTIVE, Kind.CTA_BUTTON -> 1.55f * dp
            Kind.CODE_SURFACE -> 1.0f * dp
            else -> 1.3f * dp
        }
        val rimColors = when {
            kind == Kind.PILL_ACTIVE || kind == Kind.CTA_BUTTON -> intArrayOf(
                Color.argb(250, 255, 255, 255),
                blendWithAlpha(accent.glowHex, 170),
                Color.argb(230, 255, 255, 255),
            )
            isLight -> intArrayOf(
                Color.argb(245, 255, 255, 255),
                Color.argb(145, 180, 200, 225),
                Color.argb(225, 255, 255, 255),
            )
            else -> intArrayOf(
                Color.argb(225, 255, 255, 255),
                Color.argb(82, 255, 255, 255),
                blendWithAlpha(accent.glowHex, 135),
            )
        }
        rimPaint.shader = LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            rimColors, floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, rimPaint)
    }

    private fun blendWithAlpha(rgb: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))

    override fun setAlpha(alpha: Int) {
        bodyPaint.alpha = alpha
        rimPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        bodyPaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
