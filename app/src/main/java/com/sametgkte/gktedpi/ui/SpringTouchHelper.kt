package com.sametgkte.gktedpi.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

@SuppressLint("ClickableViewAccessibility")
fun View.applyLiquidPressEffect(scaleDown: Float = 0.965f) {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                v.animate()
                    .scaleX(scaleDown)
                    .scaleY(scaleDown)
                    .setDuration(95L)
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(OvershootInterpolator(1.8f))
                    .start()
            }
        }
        false
    }
}
