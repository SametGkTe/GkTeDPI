package com.sametgkte.gktedpi.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sametgkte.gktedpi.R

/**
 * Recursively applies live Optical Liquid Glass drawables & theme-aware typography contrast
 * across any View tree so EVERY screen in GkTeDPI renders as authentic Liquid Glass.
 */
object LiquidGlassHelper {

    fun tagAndApply(view: View, kind: LiquidGlassDrawable.Kind) {
        view.setTag(R.id.tag_liquid_glass_kind, kind)
        view.background = LiquidGlassDrawable(view.context, kind)
        if (view is TextView &&
            (kind == LiquidGlassDrawable.Kind.CTA_BUTTON || kind == LiquidGlassDrawable.Kind.PILL_ACTIVE)
        ) {
            view.setTextColor(Color.WHITE)
        }
    }

    fun refreshTree(root: View) {
        val ctx = root.context
        val cfg = PersonalizationManager.getConfig(ctx)
        val primaryTxt = cfg.primaryTextColor(ctx)
        val secondaryTxt = cfg.secondaryTextColor(ctx)

        val taggedKind = root.getTag(R.id.tag_liquid_glass_kind) as? LiquidGlassDrawable.Kind
        if (taggedKind != null) {
            val existing = root.background
            if (existing is LiquidGlassDrawable && existing.kind == taggedKind) {
                existing.invalidateSelf()
            } else {
                root.background = LiquidGlassDrawable(ctx, taggedKind)
            }
        }

        when (root) {
            is EditText -> {
                root.setTextColor(primaryTxt)
                root.setHintTextColor(
                    Color.argb(
                        150,
                        Color.red(secondaryTxt),
                        Color.green(secondaryTxt),
                        Color.blue(secondaryTxt)
                    )
                )
            }
            is TextView -> {
                val bg = root.background
                val insideHeroAccent = (root.parent as? View)?.getTag(R.id.tag_liquid_glass_kind) == LiquidGlassDrawable.Kind.HERO_ACCENT_CARD ||
                    ((root.parent?.parent as? View)?.getTag(R.id.tag_liquid_glass_kind) == LiquidGlassDrawable.Kind.HERO_ACCENT_CARD)
                if (insideHeroAccent || (bg is LiquidGlassDrawable &&
                        (bg.kind == LiquidGlassDrawable.Kind.CTA_BUTTON ||
                            bg.kind == LiquidGlassDrawable.Kind.PILL_ACTIVE ||
                            bg.kind == LiquidGlassDrawable.Kind.HERO_ACCENT_CARD))
                ) {
                    root.setTextColor(Color.WHITE)
                } else {
                    root.setTextColor(primaryTxt)
                }
            }
            is ImageButton -> {
                root.imageTintList = ColorStateList.valueOf(primaryTxt)
            }
        }

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                refreshTree(root.getChildAt(i))
            }
        }
    }

    fun upgradeScreen(root: View) {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                when {
                    child is EditText -> tagAndApply(child, LiquidGlassDrawable.Kind.CODE_SURFACE)
                    child is ImageButton -> tagAndApply(child, LiquidGlassDrawable.Kind.PILL_MUTED)
                    child is TextView && child.isClickable -> tagAndApply(child, LiquidGlassDrawable.Kind.PILL_ACTIVE)
                    child is RecyclerView && child.background != null -> {
                        tagAndApply(child, LiquidGlassDrawable.Kind.CARD)
                    }
                    child is ScrollView && child.background != null -> {
                        tagAndApply(child, LiquidGlassDrawable.Kind.CARD)
                        upgradeScreen(child)
                    }
                    child is ViewGroup && child.background != null -> {
                        tagAndApply(child, LiquidGlassDrawable.Kind.CARD)
                        upgradeScreen(child)
                    }
                    else -> upgradeScreen(child)
                }
            }
        }
        refreshTree(root)
    }
}
