package com.sametgkte.gktedpi.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.sametgkte.gktedpi.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real Optical Liquid Glass Bottom Navigation Bar integrated with PersonalizationManager.
 */
class LiquidGlassBottomBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    data class TabItem(val title: String, val icon: Drawable?)

    private val tabs: List<TabItem> = listOf(
        TabItem(
            context.getString(R.string.tab_home),
            ContextCompat.getDrawable(context, R.drawable.ic_tab_home)?.mutate()
        ),
        TabItem(
            context.getString(R.string.tab_utilities),
            ContextCompat.getDrawable(context, R.drawable.ic_tab_utilities)?.mutate()
        ),
        TabItem(
            context.getString(R.string.tab_settings),
            ContextCompat.getDrawable(context, R.drawable.ic_tab_settings)?.mutate()
        ),
    )

    private val boldTypeface: Typeface = try {
        ResourcesCompat.getFont(context, R.font.pjs_bold) ?: Typeface.DEFAULT_BOLD
    } catch (_: Exception) {
        Typeface.DEFAULT_BOLD
    }

    var onTabSelected: ((Int) -> Unit)? = null

    var selectedIndex: Int = 0
        private set

    private var dropletLead = 0f
    private var dropletTrail = 0f
    private var dropletAnimator: ValueAnimator? = null

    private var touchActive = false
    private var touchX = 0f
    private var touchY = 0f

    private var backdropSource: View? = null
    private var backdropBitmap: Bitmap? = null
    private var backdropCanvas: Canvas? = null
    private var isCapturingBackdrop = false
    private val srcLoc = IntArray(2)
    private val selfLoc = IntArray(2)
    private val drawMatrix = Matrix()

    private val outerRect = RectF()
    private val innerMeniscusRect = RectF()
    private val dropletRect = RectF()
    private val outerClipPath = Path()
    private val innerClipPath = Path()
    private val dropletPath = Path()

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val causticPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val chromaticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dropletFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dropletRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = boldTypeface
        textAlign = Paint.Align.CENTER
    }

    private val personalizationListener: () -> Unit = { invalidate() }

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (!isCapturingBackdrop && isShown && width > 0 && height > 0) {
            captureAndBlurBackdrop()
        }
        true
    }

    fun bindBackdropSource(source: View) {
        if (backdropSource === source) return
        detachBackdropListener()
        backdropSource = source
        if (isAttachedToWindow) {
            source.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        PersonalizationManager.addListener(personalizationListener)
        backdropSource?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        PersonalizationManager.removeListener(personalizationListener)
        detachBackdropListener()
        dropletAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun detachBackdropListener() {
        val src = backdropSource ?: return
        if (src.viewTreeObserver.isAlive) {
            src.viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        }
    }

    private fun triggerHaptic() {
        if (PersonalizationManager.getConfig(context).hapticEnabled) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    fun selectTab(index: Int, notify: Boolean = true, animate: Boolean = true) {
        val clamped = index.coerceIn(0, tabs.lastIndex)
        val changed = clamped != selectedIndex
        selectedIndex = clamped

        dropletAnimator?.cancel()
        if (!animate) {
            dropletLead = clamped.toFloat()
            dropletTrail = clamped.toFloat()
            invalidate()
        } else {
            val startLead = dropletLead
            val startTrail = dropletTrail
            val target = clamped.toFloat()
            val movingRight = target >= startLead

            dropletAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 420L
                interpolator = OvershootInterpolator(1.15f)
                addUpdateListener { anim ->
                    val t = anim.animatedValue as Float
                    val fastT = (t * 1.32f).coerceAtMost(1f)
                    val slowT = (t * t).coerceIn(0f, 1f)
                    if (movingRight) {
                        dropletLead = startLead + (target - startLead) * fastT
                        dropletTrail = startTrail + (target - startTrail) * slowT
                    } else {
                        dropletLead = startLead + (target - startLead) * slowT
                        dropletTrail = startTrail + (target - startTrail) * fastT
                    }
                    invalidate()
                }
                start()
            }
        }

        if (changed && notify) {
            onTabSelected?.invoke(clamped)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val tabW = w / tabs.size

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchActive = true
                touchX = event.x
                touchY = event.y
                triggerHaptic()
                animate().scaleX(1.018f).scaleY(1.025f).setDuration(110L).start()
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                touchX = event.x.coerceIn(0f, w)
                touchY = event.y.coerceIn(0f, height.toFloat())
                val hoverPos = ((touchX / tabW) - 0.5f).coerceIn(0f, (tabs.size - 1).toFloat())
                dropletAnimator?.cancel()
                dropletLead = hoverPos + 0.08f
                dropletTrail = hoverPos - 0.08f
                val hoveredIdx = (touchX / tabW).toInt().coerceIn(0, tabs.lastIndex)
                if (hoveredIdx != selectedIndex) {
                    selectedIndex = hoveredIdx
                    triggerHaptic()
                    onTabSelected?.invoke(hoveredIdx)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchActive = false
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(240L)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .start()
                val tappedIdx = (event.x / tabW).toInt().coerceIn(0, tabs.lastIndex)
                selectTab(tappedIdx, notify = true, animate = true)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun captureAndBlurBackdrop() {
        val src = backdropSource ?: return
        if (width <= 8 || height <= 8) return
        val cfg = PersonalizationManager.getConfig(context)
        val downscale = 5
        val bw = (width / downscale).coerceAtLeast(16)
        val bh = (height / downscale).coerceAtLeast(8)

        var bmp = backdropBitmap
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            backdropBitmap = bmp
            backdropCanvas = Canvas(bmp)
        }
        val c = backdropCanvas ?: return

        src.getLocationInWindow(srcLoc)
        getLocationInWindow(selfLoc)
        val dx = (selfLoc[0] - srcLoc[0]).toFloat()
        val dy = (selfLoc[1] - srcLoc[1]).toFloat()

        isCapturingBackdrop = true
        try {
            c.save()
            c.drawColor(if (cfg.isLightResolved(context)) Color.parseColor("#E6EEF8") else Color.BLACK)
            c.scale(1f / downscale, 1f / downscale)
            c.translate(-dx, -dy)
            src.draw(c)
            c.restore()
            fastBoxBlur(bmp, radius = 2 + cfg.frostLevel)
        } catch (_: Exception) {
        } finally {
            isCapturingBackdrop = false
        }
    }

    private fun fastBoxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        if (w <= radius * 2 || h <= radius * 2) return
        val pixels = IntArray(w * h)
        val temp = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val div = radius * 2 + 1

        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (k in -radius..radius) {
                    val px = pixels[row + (x + k).coerceIn(0, w - 1)]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                temp[row + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
            }
        }
        for (x in 0 until w) {
            for (y in 0 until h) {
                var rSum = 0; var gSum = 0; var bSum = 0
                for (k in -radius..radius) {
                    val px = temp[(y + k).coerceIn(0, h - 1) * w + x]
                    rSum += (px shr 16) and 0xFF
                    gSum += (px shr 8) and 0xFF
                    bSum += px and 0xFF
                }
                pixels[y * w + x] = (0xFF shl 24) or ((rSum / div) shl 16) or ((gSum / div) shl 8) or (bSum / div)
            }
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 4f || h <= 4f) return

        val cfg = PersonalizationManager.getConfig(context)
        val isLight = cfg.isLightResolved(context)
        val accent = cfg.accent
        val dp = resources.displayMetrics.density

        val pad = 1.5f * dp
        outerRect.set(pad, pad, w - pad, h - pad)
        val radius = outerRect.height() / 2f

        outerClipPath.reset()
        outerClipPath.addRoundRect(outerRect, radius, radius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(outerClipPath)

        // 1. Live Refracted Backdrop
        val bmp = backdropBitmap
        if (bmp != null) {
            val scaleBaseX = w / bmp.width.toFloat()
            val scaleBaseY = h / bmp.height.toFloat()
            val zoomRim = 1.06f + 0.05f * cfg.refractionLevel

            drawMatrix.reset()
            drawMatrix.postScale(scaleBaseX * zoomRim, scaleBaseY * (zoomRim + 0.06f))
            drawMatrix.postTranslate(-w * (zoomRim - 1f) * 0.5f, -h * (zoomRim - 0.94f) * 0.5f)
            canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)

            val bevel = 6.5f * dp
            innerMeniscusRect.set(
                outerRect.left + bevel,
                outerRect.top + bevel,
                outerRect.right - bevel,
                outerRect.bottom - bevel
            )
            val innerR = innerMeniscusRect.height() / 2f
            innerClipPath.reset()
            innerClipPath.addRoundRect(innerMeniscusRect, innerR, innerR, Path.Direction.CW)

            canvas.save()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                canvas.clipPath(innerClipPath)
            } else {
                @Suppress("DEPRECATION")
                canvas.clipPath(innerClipPath, Region.Op.INTERSECT)
            }
            drawMatrix.reset()
            drawMatrix.postScale(scaleBaseX * 1.02f, scaleBaseY * 1.03f)
            drawMatrix.postTranslate(-w * 0.01f, -h * 0.015f)
            canvas.drawBitmap(bmp, drawMatrix, bitmapPaint)
            canvas.restore()
        }

        // 2. Liquid Crystal Tint
        val frostBoost = cfg.frostLevel * 22
        tintPaint.shader = if (isLight) {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.argb((150 + frostBoost).coerceAtMost(220), 255, 255, 255),
                    Color.argb((125 + frostBoost).coerceAtMost(205), 236, 244, 255),
                    Color.argb((145 + frostBoost).coerceAtMost(215), 228, 238, 252),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.argb((120 + frostBoost).coerceAtMost(210), 28, 38, 54),
                    Color.argb((148 + frostBoost).coerceAtMost(225), 11, 16, 24),
                    Color.argb((165 + frostBoost).coerceAtMost(235), 6, 10, 16),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(outerRect, tintPaint)

        // 3. Top Dome Specular Sheen
        sheenPaint.shader = LinearGradient(
            0f, outerRect.top, 0f, outerRect.top + h * 0.48f,
            intArrayOf(
                Color.argb(if (isLight) 140 else 78, 255, 255, 255),
                Color.argb(18, 255, 255, 255),
                Color.TRANSPARENT,
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(outerRect.left, outerRect.top, outerRect.right, outerRect.top + h * 0.5f, sheenPaint)

        // 4. Finger-Tracking Caustic Light
        if (touchActive) {
            val cRad = h * 1.35f
            causticPaint.shader = RadialGradient(
                touchX, touchY, cRad,
                intArrayOf(
                    Color.argb(95, 255, 255, 255),
                    withAlpha(accent.glowHex, 52),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(touchX, touchY, cRad, causticPaint)
        }

        // 5. Viscous Liquid Droplet Active Tab Capsule
        val tabW = outerRect.width() / tabs.size
        val minIdx = min(dropletLead, dropletTrail)
        val maxIdx = max(dropletLead, dropletTrail)
        val inset = 5f * dp
        val dLeft = outerRect.left + minIdx * tabW + inset
        val dRight = outerRect.left + (maxIdx + 1f) * tabW - inset
        val dTop = outerRect.top + inset
        val dBot = outerRect.bottom - inset
        val stretchAmount = abs(dropletLead - dropletTrail).coerceAtMost(0.8f)
        val squishY = stretchAmount * 2.4f * dp
        dropletRect.set(dLeft, dTop + squishY, dRight, dBot - squishY)
        val dRad = dropletRect.height() / 2f

        dropletFillPaint.shader = LinearGradient(
            dropletRect.left, dropletRect.top, dropletRect.right, dropletRect.bottom,
            intArrayOf(
                withAlpha(accent.primaryHex, 195),
                withAlpha(accent.secondaryHex, 165),
                withAlpha(accent.glowHex, 130),
            ),
            floatArrayOf(0f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        dropletPath.reset()
        dropletPath.addRoundRect(dropletRect, dRad, dRad, Path.Direction.CW)
        canvas.drawPath(dropletPath, dropletFillPaint)

        dropletRimPaint.strokeWidth = 1.35f * dp
        dropletRimPaint.shader = LinearGradient(
            dropletRect.left, dropletRect.top, dropletRect.right, dropletRect.bottom,
            intArrayOf(
                Color.argb(248, 255, 255, 255),
                Color.argb(125, 255, 255, 255),
                withAlpha(accent.glowHex, 215),
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(dropletRect, dRad, dRad, dropletRimPaint)

        canvas.restore()

        // 6. Chromatic Rim + Specular Glass Border
        if (cfg.refractionLevel > 0) {
            val chAlpha = if (cfg.refractionLevel == 2) 80 else 42
            chromaticPaint.strokeWidth = 2.4f * dp
            chromaticPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(
                    Color.argb(chAlpha, 56, 189, 248),
                    Color.TRANSPARENT,
                    withAlpha(accent.glowHex, chAlpha),
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(
                outerRect.left + dp, outerRect.top + dp,
                outerRect.right - dp, outerRect.bottom - dp,
                radius, radius, chromaticPaint
            )
        }

        rimPaint.strokeWidth = 1.5f * dp
        rimPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(
                Color.argb(250, 255, 255, 255),
                Color.argb(125, 255, 255, 255),
                withAlpha(accent.glowHex, 195),
            ),
            floatArrayOf(0f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(outerRect, radius, radius, rimPaint)

        // 7. Crisp Icons & Labels
        val iconSize = (21f * dp).toInt()
        labelPaint.textSize = 11.5f * dp
        val centerIdx = (dropletLead + dropletTrail) * 0.5f

        for ((idx, item) in tabs.withIndex()) {
            val cx = outerRect.left + tabW * (idx + 0.5f)
            val dist = abs(idx - centerIdx).coerceAtMost(1f)
            val isOnDroplet = dist < 0.48f
            val itemColor = if (isOnDroplet || !isLight) Color.WHITE else Color.parseColor("#0F172A")
            val alpha = (255 - (dist * 45).toInt()).coerceIn(195, 255)

            item.icon?.let { d ->
                val top = (outerRect.top + 11f * dp).toInt()
                val half = iconSize / 2
                d.setTint(itemColor)
                d.setBounds((cx - half).toInt(), top, (cx + half).toInt(), top + iconSize)
                d.alpha = alpha
                d.draw(canvas)
            }

            labelPaint.color = itemColor
            labelPaint.alpha = alpha
            canvas.drawText(item.title, cx, outerRect.bottom - 11.5f * dp, labelPaint)
        }
    }

    private fun withAlpha(rgb: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(rgb), Color.green(rgb), Color.blue(rgb))
}
