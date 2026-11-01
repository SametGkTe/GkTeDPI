package com.sametgkte.gktedpi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Full-Screen Backdrop & Wallpaper Engine behind all Liquid Glass surfaces.
 *
 * - Default (`WallpaperStyle.PURE_BLACK`): 100% pure `#000000` full black behind Liquid Glass!
 * - Built-in Wallpapers (`EMERALD_AURORA`, `COSMIC_NEBULA`, `CRIMSON_OBSIDIAN`, `CARBON_MATRIX`)
 * - Custom Gallery Photo (`CUSTOM_PHOTO`): Center-cropped user image with configurable dimming.
 */
class LiquidAmbientBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(26, 255, 255, 255)
    }
    private val imgMatrix = Matrix()
    private var connected = false
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (Math.PI * 2.0).toFloat()).apply {
        duration = 15000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    private val configListener: () -> Unit = {
        syncAnimationState()
        invalidate()
    }

    fun setConnectedState(isConnected: Boolean) {
        if (connected != isConnected) {
            connected = isConnected
            invalidate()
        }
    }

    private fun syncAnimationState() {
        val cfg = PersonalizationManager.getConfig(context)
        val needsMotion = cfg.animatedAmbient && (
            cfg.wallpaperStyle == PersonalizationManager.WallpaperStyle.EMERALD_AURORA ||
                cfg.wallpaperStyle == PersonalizationManager.WallpaperStyle.COSMIC_NEBULA ||
                cfg.wallpaperStyle == PersonalizationManager.WallpaperStyle.CRIMSON_OBSIDIAN
            )
        if (needsMotion && isAttachedToWindow) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PersonalizationManager.addListener(configListener)
        syncAnimationState()
    }

    override fun onDetachedFromWindow() {
        PersonalizationManager.removeListener(configListener)
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return

        val cfg = PersonalizationManager.getConfig(context)
        val isLight = cfg.isLightResolved(context)

        when (cfg.wallpaperStyle) {
            PersonalizationManager.WallpaperStyle.PURE_BLACK -> {
                if (isLight) {
                    basePaint.shader = LinearGradient(
                        0f, 0f, w, h,
                        intArrayOf(
                            Color.parseColor("#F4F7FC"),
                            Color.parseColor("#E9EFF8"),
                            Color.parseColor("#E2EAF6"),
                        ),
                        floatArrayOf(0f, 0.5f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(0f, 0f, w, h, basePaint)
                } else if (cfg.themeMode == PersonalizationManager.ThemeMode.DARK_GLASS) {
                    // Even in Gece Camı, keep ultra-deep near-black unless AMOLED (#000000)
                    canvas.drawColor(Color.parseColor("#04070D"))
                } else {
                    // 100% Pure Full Black (#000000) behind Liquid Glass!
                    canvas.drawColor(Color.BLACK)
                }
            }

            PersonalizationManager.WallpaperStyle.CUSTOM_PHOTO -> {
                canvas.drawColor(Color.BLACK)
                val bmp = PersonalizationManager.getCustomWallpaperBitmap(context)
                if (bmp != null && !bmp.isRecycled) {
                    drawCenterCropBitmap(canvas, bmp, w, h)
                    val dimAlpha = when (cfg.wallpaperDimLevel) {
                        0 -> 68   // ~27%
                        2 -> 178  // ~70%
                        else -> 122 // ~48%
                    }
                    canvas.drawColor(Color.argb(dimAlpha, 0, 0, 0))
                }
            }

            PersonalizationManager.WallpaperStyle.CARBON_MATRIX -> {
                canvas.drawColor(Color.BLACK)
                val step = 22f * resources.displayMetrics.density
                val dotRadius = 1.15f * resources.displayMetrics.density
                var y = step * 0.5f
                while (y < h) {
                    var x = step * 0.5f
                    while (x < w) {
                        canvas.drawCircle(x, y, dotRadius, gridPaint)
                        x += step
                    }
                    y += step
                }
                drawOrb(canvas, w * 0.5f, h * 0.18f, max(w, h) * 0.45f, cfg.accent.primaryHex, 26)
            }

            PersonalizationManager.WallpaperStyle.EMERALD_AURORA -> {
                canvas.drawColor(Color.BLACK)
                drawWallpaperOrbs(
                    canvas, w, h,
                    c1 = Color.parseColor("#10B981"),
                    c2 = Color.parseColor("#06B6D4"),
                    c3 = Color.parseColor("#047857"),
                    cfg.wallpaperDimLevel
                )
            }

            PersonalizationManager.WallpaperStyle.COSMIC_NEBULA -> {
                canvas.drawColor(Color.BLACK)
                drawWallpaperOrbs(
                    canvas, w, h,
                    c1 = Color.parseColor("#6366F1"),
                    c2 = Color.parseColor("#0EA5E9"),
                    c3 = Color.parseColor("#A855F7"),
                    cfg.wallpaperDimLevel
                )
            }

            PersonalizationManager.WallpaperStyle.CRIMSON_OBSIDIAN -> {
                canvas.drawColor(Color.BLACK)
                drawWallpaperOrbs(
                    canvas, w, h,
                    c1 = Color.parseColor("#F43F5E"),
                    c2 = Color.parseColor("#FB923C"),
                    c3 = Color.parseColor("#9F1239"),
                    cfg.wallpaperDimLevel
                )
            }
        }
    }

    private fun drawCenterCropBitmap(canvas: Canvas, bmp: Bitmap, vw: Float, vh: Float) {
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0f || bh <= 0f) return
        val scale = max(vw / bw, vh / bh)
        val dx = (vw - bw * scale) * 0.5f
        val dy = (vh - bh * scale) * 0.5f
        imgMatrix.reset()
        imgMatrix.setScale(scale, scale)
        imgMatrix.postTranslate(dx, dy)
        canvas.drawBitmap(bmp, imgMatrix, bmpPaint)
    }

    private fun drawWallpaperOrbs(
        canvas: Canvas,
        w: Float,
        h: Float,
        c1: Int,
        c2: Int,
        c3: Int,
        dimLevel: Int,
    ) {
        val maxDim = max(w, h)
        val mult = when (dimLevel) {
            0 -> 1.25f
            2 -> 0.55f
            else -> 0.90f
        }
        val x1 = w * (0.22f + 0.16f * cos(phase))
        val y1 = h * (0.20f + 0.10f * sin(phase))
        drawOrb(canvas, x1, y1, maxDim * 0.50f, c1, (92 * mult).toInt().coerceIn(18, 150))

        val x2 = w * (0.80f + 0.14f * sin(phase * 0.8f + 1.4f))
        val y2 = h * (0.52f + 0.14f * cos(phase * 0.8f))
        drawOrb(canvas, x2, y2, maxDim * 0.44f, c2, (74 * mult).toInt().coerceIn(16, 135))

        val x3 = w * (0.42f + 0.18f * sin(phase + 2.6f))
        val y3 = h * (0.86f + 0.08f * cos(phase))
        drawOrb(canvas, x3, y3, maxDim * 0.46f, c3, (80 * mult).toInt().coerceIn(16, 140))
    }

    private fun drawOrb(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        colorHex: Int,
        alpha: Int,
    ) {
        val r = Color.red(colorHex)
        val g = Color.green(colorHex)
        val b = Color.blue(colorHex)
        blobPaint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(
                Color.argb(alpha, r, g, b),
                Color.argb((alpha * 0.42f).toInt(), r, g, b),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius, blobPaint)
    }
}
