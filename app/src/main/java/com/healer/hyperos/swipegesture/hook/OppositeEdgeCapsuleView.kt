package com.healer.hyperos.swipegesture.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.healer.hyperos.swipegesture.ipc.CandidateApp
import com.healer.hyperos.swipegesture.ipc.OverlayShowPayload
import kotlin.math.roundToInt

/**
 * Opposite-edge frosted-glass vertical capsule overlay rendered inside `com.android.systemui`.
 *
 * - Appears on the edge opposite the swipe origin to avoid thumb occlusion.
 * - Highlights the active candidate with scale emphasis and a companion label pill.
 * - Attempts HyperOS native MiBlur reflection safely, gracefully falling back to translucent glass.
 */
internal class OppositeEdgeCapsuleView(
    context: Context,
    private val payload: OverlayShowPayload,
) : LinearLayout(context) {

    private val isDark =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val density = context.resources.displayMetrics.density
    private val scale = payload.scale.coerceIn(0.7f, 1.4f)
    private val slotSizePx = dp(44f)
    private val slotGapPx = dp(6f)
    private val capsulePadHPx = dp(8f)
    private val capsulePadVPx = dp(10f)

    private val slotContainers = ArrayList<FrameLayout>(payload.candidates.size)
    private val slotIcons = ArrayList<ImageView>(payload.candidates.size)
    private val activeLabelView: TextView
    private var selectedIndex: Int = payload.selectedIndex.coerceIn(0, (payload.candidates.size - 1).coerceAtLeast(0))

    val estimatedHeightPx: Int
        get() {
            val count = payload.candidates.size.coerceAtLeast(1)
            return capsulePadVPx * 2 + count * slotSizePx + (count - 1) * slotGapPx
        }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        activeLabelView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * scale)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#181C24"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(128f)
            setPadding(dp(12f), dp(7f), dp(12f), dp(7f))
            background = createGlassPillDrawable(radiusPx = dp(16f).toFloat())
            elevation = dp(4f).toFloat()
        }

        val capsuleColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(capsulePadHPx, capsulePadVPx, capsulePadHPx, capsulePadVPx)
            background = createGlassPillDrawable(radiusPx = ((slotSizePx + capsulePadHPx * 2) / 2f))
            elevation = dp(6f).toFloat()
            tryApplyHyperOsBlur(this)
        }

        val pm = context.packageManager
        payload.candidates.forEachIndexed { index, candidate ->
            if (index > 0) {
                capsuleColumn.addView(View(context), LayoutParams(1, slotGapPx))
            }
            val slot = buildSlot(candidate, pm)
            slotContainers.add(slot)
            capsuleColumn.addView(slot, LayoutParams(slotSizePx, slotSizePx))
        }

        val labelParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            if (payload.fromLeft) {
                marginEnd = dp(10f)
            } else {
                marginStart = dp(10f)
            }
        }

        // If swiped from left edge, capsule docks on the right edge, so label appears to its left.
        if (payload.fromLeft) {
            addView(activeLabelView, labelParams)
            addView(capsuleColumn)
        } else {
            addView(capsuleColumn)
            addView(activeLabelView, labelParams)
        }

        applySelectionVisuals(animate = false)
    }

    fun animateEnter() {
        alpha = 0f
        scaleX = 0.92f
        scaleY = 0.92f
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    fun updateSelection(index: Int) {
        if (index !in payload.candidates.indices || index == selectedIndex) return
        selectedIndex = index
        applySelectionVisuals(animate = true)
    }

    private fun buildSlot(
        candidate: CandidateApp,
        pm: android.content.pm.PackageManager,
    ): FrameLayout {
        val slot = FrameLayout(context)
        val iconSize = (slotSizePx * 0.68f).roundToInt()
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val drawable: Drawable? = runCatching { pm.getApplicationIcon(candidate.packageName) }.getOrNull()
            setImageDrawable(drawable)
        }
        slotIcons.add(iconView)
        slot.addView(
            iconView,
            FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER),
        )
        return slot
    }

    private fun applySelectionVisuals(animate: Boolean) {
        val selectedCandidate = payload.candidates.getOrNull(selectedIndex)
        activeLabelView.text = selectedCandidate?.label.orEmpty()

        for (i in slotContainers.indices) {
            val active = i == selectedIndex
            val container = slotContainers[i]
            val icon = slotIcons[i]

            container.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    when {
                        active && isDark -> Color.argb(96, 255, 255, 255)
                        active -> Color.argb(46, 24, 114, 255)
                        isDark -> Color.argb(28, 255, 255, 255)
                        else -> Color.argb(20, 0, 0, 0)
                    },
                )
                if (active) {
                    setStroke(
                        dp(1.5f).coerceAtLeast(1),
                        if (isDark) Color.argb(210, 255, 255, 255) else Color.argb(190, 24, 114, 255),
                    )
                }
            }

            val targetScale = if (active) 1.12f else 0.92f
            val targetAlpha = if (active) 1.0f else 0.62f
            if (animate) {
                icon.animate()
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .alpha(targetAlpha)
                    .setDuration(110L)
                    .start()
            } else {
                icon.scaleX = targetScale
                icon.scaleY = targetScale
                icon.alpha = targetAlpha
            }
        }
    }

    private fun createGlassPillDrawable(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(
            if (isDark) Color.argb(218, 24, 28, 36) else Color.argb(228, 248, 250, 255),
        )
        setStroke(
            dp(1f).coerceAtLeast(1),
            if (isDark) Color.argb(56, 255, 255, 255) else Color.argb(42, 0, 0, 0),
        )
    }

    private fun tryApplyHyperOsBlur(target: View) {
        // Optional HyperOS SystemUI blur enhancement; safely ignored if signature differs.
        runCatching {
            val method = View::class.java.getMethod(
                "setMiBackgroundBlurMode",
                Int::class.javaPrimitiveType,
            )
            method.invoke(target, 1)
            val radiusMethod = View::class.java.getMethod(
                "setMiBackgroundBlurRadius",
                Int::class.javaPrimitiveType,
            )
            radiusMethod.invoke(target, dp(32f))
        }
    }

    private fun dp(value: Float): Int = (value * density * scale).roundToInt().coerceAtLeast(1)
}
