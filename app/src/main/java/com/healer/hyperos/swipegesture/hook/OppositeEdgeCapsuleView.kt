package com.healer.hyperos.swipegesture.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.healer.hyperos.swipegesture.ipc.CandidateApp
import com.healer.hyperos.swipegesture.ipc.OverlayShowPayload
import com.healer.hyperos.swipegesture.policy.OverlayMaterial
import com.healer.hyperos.swipegesture.policy.OverlayThemeMode
import com.healer.hyperos.swipegesture.policy.SwitchMode
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Opposite-edge vertical icon capsule overlay rendered inside `com.android.systemui`.
 *
 * Combines HyperOS 4's native cross-window blur pipeline (`miuix.core.util.MiuiBlurUtils` +
 * `HyperBloomStrokeUtils` + `MaterialDayNightConfig`) with deterministic optical glass cues so
 * the capsule stands out crisply against both pure white (`#FFFFFF`) and OLED black (`#000000`)
 * backgrounds:
 * 1. Dual-layer GPU-accelerated floating shadow drawn outside the pill via `clipOutPath` (so
 *    live blur inside the capsule stays crystal-clear and shadow never gets clipped or zeroed).
 * 2. Live cross-window blur with explicit rounded-pill `ViewOutlineProvider` (`outline.alpha = 1f`).
 * 3. Subtle optical tonal separation veil (cool pearl-silver in Light mode, elevated smoky-slate
 *    in Dark mode) plus a directional top specular sheen in [OverlayMaterial.SOFT_LIGHT_GLASS].
 * 4. Dual-edge glass bezel (outer framing hairline + inner bright specular catchlight rim).
 * 5. Uniform full-size icons: every icon is rendered at the same enlarged size with no zoom
 *    animation; selection is indicated purely by a crisp HyperOS blue circular ring.
 */
internal class OppositeEdgeCapsuleView(
    context: Context,
    private val payload: OverlayShowPayload,
) : FrameLayout(context) {

    private val isDark: Boolean = when (payload.themeMode) {
        OverlayThemeMode.LIGHT -> false
        OverlayThemeMode.DARK -> true
        OverlayThemeMode.FOLLOW_SYSTEM ->
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private val density = context.resources.displayMetrics.density
    private val displayedCandidates = if (payload.mode == SwitchMode.QUICK_PREVIOUS) {
        payload.candidates.take(1)
    } else {
        payload.candidates
    }
    private val fullDisplayHeightPx: Int = runCatching {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        wm?.maximumWindowMetrics?.bounds?.height()
    }.getOrNull()?.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels

    private val scale: Float = run {
        val rawScale = payload.scale.coerceIn(0.7f, 1.4f)
        val count = displayedCandidates.size.coerceAtLeast(1)
        val baseHeightPx = (60f + count * 46f + (count - 1) * 7f) * density
        val maxHeightPx = (fullDisplayHeightPx * 0.84f).coerceAtLeast(120f)
        minOf(rawScale, maxHeightPx / baseHeightPx).coerceAtLeast(0.55f)
    }

    val effectiveScale: Float
        get() = scale
    private val slotSizePx = dp(46f)
    private val slotGapPx = dp(7f)
    private val capsulePadHPx = dp(8f)
    private val capsulePadVPx = dp(8f)
    private val edgePadPx = dp(15f)
    private val innerPadPx = dp(22f)
    private val verticalPadPx = dp(22f)
    private val padLeftPx = if (payload.fromLeft) innerPadPx else edgePadPx
    private val padRightPx = if (payload.fromLeft) edgePadPx else innerPadPx
    private val capsuleWidthPx = slotSizePx + capsulePadHPx * 2
    private val capsuleHeightPx: Int = run {
        val count = displayedCandidates.size.coerceAtLeast(1)
        capsulePadVPx * 2 + count * slotSizePx + (count - 1) * slotGapPx
    }
    /** Matches HyperOS 4 volume slider corner-to-width ratio (`0.368 * width`, G2 smooth corner). */
    private val capsuleCornerRadiusPx = capsuleWidthPx * VOLUME_CORNER_TO_WIDTH_RATIO

    private val slotContainers = ArrayList<FrameLayout>(displayedCandidates.size)
    private val slotBackgrounds = ArrayList<GradientDrawable>(displayedCandidates.size)
    private var selectedIndex: Int =
        payload.selectedIndex.coerceIn(0, (displayedCandidates.size - 1).coerceAtLeast(0))

    // Shadow geometry & G2 smooth-corner paths drawn outside the glass bar on parent FrameLayout.
    private val capsuleRect = RectF(
        padLeftPx.toFloat(),
        verticalPadPx.toFloat(),
        (padLeftPx + capsuleWidthPx).toFloat(),
        (verticalPadPx + capsuleHeightPx).toFloat(),
    )
    private val capsuleClipPath = Path().also {
        buildSmoothRectPath(context, it, capsuleRect, capsuleCornerRadiusPx)
    }
    private val ambientShadowPath = Path().also {
        val rect = RectF(capsuleRect).apply { offset(0f, dpF(3.5f)) }
        buildSmoothRectPath(context, it, rect, capsuleCornerRadiusPx)
    }
    private val contactShadowPath = Path().also {
        val rect = RectF(capsuleRect).apply { offset(0f, dpF(1.2f)) }
        buildSmoothRectPath(context, it, rect, capsuleCornerRadiusPx)
    }
    private val ambientShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (isDark) Color.argb(148, 0, 0, 0) else Color.argb(56, 15, 23, 42)
        maskFilter = BlurMaskFilter(dpF(10.5f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }
    private val contactShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = if (isDark) Color.argb(118, 0, 0, 0) else Color.argb(46, 15, 23, 42)
        maskFilter = BlurMaskFilter(dpF(3.2f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }

    val appliedMaterialSummary: String

    val estimatedWidthPx: Int
        get() = padLeftPx + capsuleWidthPx + padRightPx

    val estimatedHeightPx: Int
        get() = verticalPadPx * 2 + capsuleHeightPx

    val edgeInsetOffsetPx: Int
        get() = edgePadPx

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        setPadding(padLeftPx, verticalPadPx, padRightPx, verticalPadPx)

        val pillDrawable = createFallbackGlassPillDrawable(radiusPx = capsuleCornerRadiusPx)
        val capsuleColumn = CapsuleSurfaceLayout(
            context = context,
            cornerRadiusPx = capsuleCornerRadiusPx,
            material = payload.material,
            isDark = isDark,
            dpToPx = ::dpF,
        ).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(capsulePadHPx, capsulePadVPx, capsulePadHPx, capsulePadVPx)
            background = pillDrawable
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, capsuleCornerRadiusPx)
                    outline.alpha = 1f
                }
            }
            clipToOutline = true
            clipChildren = false
            clipToPadding = false
        }
        enableViewSmoothCorner(capsuleColumn)

        val pm = context.packageManager
        displayedCandidates.forEachIndexed { index, candidate ->
            if (index > 0) {
                capsuleColumn.addView(View(context), LinearLayout.LayoutParams(1, slotGapPx))
            }
            val slot = buildSlot(candidate, pm)
            slotContainers.add(slot)
            capsuleColumn.addView(slot, LinearLayout.LayoutParams(slotSizePx, slotSizePx))
        }

        val materialResult = applyHyperOsMaterial(
            target = capsuleColumn,
            pillDrawable = pillDrawable,
            material = payload.material,
            isLight = !isDark,
        )
        capsuleColumn.liveBlurActive = materialResult.liveBlurActive
        appliedMaterialSummary = materialResult.summary

        addView(capsuleColumn, LayoutParams(capsuleWidthPx, capsuleHeightPx, Gravity.TOP or Gravity.START))
        applySelectionVisuals()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val saveCount = canvas.save()
        canvas.clipOutPath(capsuleClipPath)
        canvas.drawPath(ambientShadowPath, ambientShadowPaint)
        canvas.drawPath(contactShadowPath, contactShadowPaint)
        canvas.restoreToCount(saveCount)
    }

    fun animateEnter() {
        pivotX = if (payload.fromLeft) estimatedWidthPx.toFloat() else 0f
        pivotY = estimatedHeightPx / 2f
        val edgeShift = if (payload.fromLeft) dpF(14f) else -dpF(14f)
        alpha = 0f
        scaleX = 0.88f
        scaleY = 0.88f
        translationX = edgeShift
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .setDuration(160L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    fun animateExit(cancelled: Boolean, onEnd: () -> Unit) {
        pivotX = if (payload.fromLeft) estimatedWidthPx.toFloat() else 0f
        pivotY = estimatedHeightPx / 2f
        val edgeShift = if (payload.fromLeft) dpF(12f) else -dpF(12f)
        val targetScale = if (cancelled) 0.86f else 0.94f
        animate()
            .alpha(0f)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationX(edgeShift)
            .setDuration(110L)
            .setInterpolator(AccelerateInterpolator(1.3f))
            .withEndAction(onEnd)
            .start()
    }

    fun updateSelection(index: Int) {
        if (index !in slotContainers.indices || index == selectedIndex) return
        selectedIndex = index
        applySelectionVisuals()
    }

    private fun buildSlot(
        candidate: CandidateApp,
        pm: android.content.pm.PackageManager,
    ): FrameLayout {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        slotBackgrounds.add(bg)

        val slot = FrameLayout(context).apply {
            background = bg
        }
        val iconSize = dp(34f)
        val iconView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            val drawable: Drawable? = runCatching { pm.getApplicationIcon(candidate.packageName) }.getOrNull()
            setImageDrawable(drawable)
        }
        slot.addView(
            iconView,
            LayoutParams(iconSize, iconSize, Gravity.CENTER),
        )
        return slot
    }

    /**
     * Uniform full-size icon visuals: all icons share the same enlarged size with no zoom or
     * opacity dimming; only the selected item is framed by a crisp blue circular ring.
     */
    private fun applySelectionVisuals() {
        val activeStrokePx = dp(2.0f).coerceAtLeast(2)
        val activeRingColor = if (isDark) Color.argb(242, 77, 163, 255) else Color.argb(235, 24, 114, 255)
        for (i in slotContainers.indices) {
            val active = i == selectedIndex
            val bg = slotBackgrounds[i]
            bg.setColor(Color.TRANSPARENT)
            if (active) {
                bg.setStroke(activeStrokePx, activeRingColor)
            } else {
                bg.setStroke(0, Color.TRANSPARENT)
            }
        }
    }

    private fun applyHyperOsMaterial(
        target: View,
        pillDrawable: GradientDrawable,
        material: OverlayMaterial,
        isLight: Boolean,
    ): MaterialApplyResult {
        val miuixApplied = applyNativeMiuixMaterial(target, pillDrawable, material, isLight)
        if (miuixApplied != null) {
            return MaterialApplyResult(liveBlurActive = true, summary = miuixApplied)
        }

        val viewBlurApplied = applyDirectViewBlurFallback(target, pillDrawable, material, isLight)
        if (viewBlurApplied != null) {
            return MaterialApplyResult(liveBlurActive = true, summary = viewBlurApplied)
        }

        pillDrawable.alpha = 255
        target.elevation = 0f
        return MaterialApplyResult(
            liveBlurActive = false,
            summary = "translucent_fallback(material=$material,isLight=$isLight)",
        )
    }

    /**
     * Invokes HyperOS 4's own `miuix.core.util.MiuiBlurUtils`, `HyperBloomStrokeUtils`,
     * and `MaterialDayNightConfig` inside `com.android.systemui`.
     */
    private fun applyNativeMiuixMaterial(
        target: View,
        pillDrawable: GradientDrawable,
        material: OverlayMaterial,
        isLight: Boolean,
    ): String? = runCatching {
        val cl = context.classLoader
        val hyperMatClass = Class.forName("miuix.core.util.HyperMaterialUtils", false, cl)
        val isFeatureEnable = hyperMatClass.getMethod("isFeatureEnable", Context::class.java)
            .invoke(null, context) as? Boolean ?: false
        if (!isFeatureEnable) return null

        val blurUtilsClass = Class.forName("miuix.core.util.MiuiBlurUtils", false, cl)
        val colorBlendTokenClass = Class.forName("miuix.theme.token.ColorBlendToken", false, cl)
        val builderClass = Class.forName("miuix.theme.token.MaterialToken\$Builder", false, cl)
        val tokenClass = Class.forName("miuix.theme.token.MaterialToken", false, cl)
        val dayNightTokenClass = Class.forName("miuix.theme.token.MaterialDayNightToken", false, cl)
        val dayNightConfigClass = Class.forName("miuix.core.util.MaterialDayNightConfig", false, cl)

        val (lightBlendName, darkBlendName, blurRadiusDp, useBloomStroke) = when (material) {
            OverlayMaterial.SOFT_LIGHT_GLASS ->
                MaterialPreset("Pured_Thick_Glass_Light", "Pured_Thick_Glass_Dark", 60, true)

            OverlayMaterial.FROSTED_BLUR ->
                MaterialPreset("Heavy_Light", "Heavy_Dark", 68, false)
        }

        val builderCtor = builderClass.getConstructor(
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
        val setColorBlend = builderClass.getMethod("setColorBlend", colorBlendTokenClass)
        val setMaskBlur = builderClass.getMethod("setMaskBlur", Int::class.javaPrimitiveType)
        val setBloomStroke = if (useBloomStroke) {
            builderClass.getMethod("setBloomStroke", FloatArray::class.java)
        } else {
            null
        }
        val bloomTokenClass = if (useBloomStroke) {
            Class.forName("miuix.theme.token.BloomStrokeToken", false, cl)
        } else {
            null
        }
        val mTokenField = builderClass.getDeclaredField("mToken").apply { isAccessible = true }

        fun buildToken(variant: String, blendFieldName: String, strokeFieldName: String): Any {
            val builder = builderCtor.newInstance(30, "hyper-swipe-capsule", variant)
            val blendToken = colorBlendTokenClass.getField(blendFieldName).get(null)
            setColorBlend.invoke(builder, blendToken)
            setMaskBlur.invoke(builder, blurRadiusDp)
            if (useBloomStroke && setBloomStroke != null && bloomTokenClass != null) {
                val stroke = bloomTokenClass.getField(strokeFieldName).get(null) as? FloatArray
                if (stroke != null) {
                    setBloomStroke.invoke(builder, stroke)
                }
            }
            return mTokenField.get(builder)!!
        }

        val lightToken = buildToken("light", lightBlendName, "Glass_Stroke_Small_Light")
        val darkToken = buildToken("dark", darkBlendName, "Glass_Stroke_Small_Dark")
        val dayNightToken = dayNightTokenClass.getConstructor(tokenClass, tokenClass)
            .newInstance(lightToken, darkToken) as Parcelable

        val dayNightConfig = dayNightConfigClass.getMethod("create", Parcelable::class.java)
            .invoke(null, dayNightToken) ?: return null
        val materialConfig = dayNightConfigClass.getMethod("get", Boolean::class.javaPrimitiveType)
            .invoke(dayNightConfig, isLight) ?: return null

        val blurConfig = materialConfig.javaClass.getField("mBlurConfig").get(materialConfig)
        val resolvedRadiusDp = blurConfig?.javaClass?.getField("blurRadius")?.getInt(blurConfig)
            ?: blurRadiusDp
        val blurRadiusPx = (resolvedRadiusDp * density).roundToInt().coerceIn(0, 400)

        val setPassWindowBlur = blurUtilsClass.getMethod(
            "setPassWindowBlurEnabled",
            View::class.java,
            Boolean::class.javaPrimitiveType,
        )
        val setBgOnlyBlur = blurUtilsClass.getMethod(
            "setBackgroundOnlyBlur",
            Int::class.javaPrimitiveType,
            View::class.java,
        )
        val setViewBlurMode = blurUtilsClass.getMethod(
            "setViewBlurMode",
            Int::class.javaPrimitiveType,
            View::class.java,
        )
        val setMiBlurWinType = blurUtilsClass.getMethod("setMiBlurWinType", View::class.java)

        // Hide solid drawable fill so RenderNode's live cross-window blur shines through cleanly.
        pillDrawable.alpha = 0
        target.elevation = 0f

        setPassWindowBlur.invoke(null, target, true)
        setBgOnlyBlur.invoke(null, blurRadiusPx, target)
        setViewBlurMode.invoke(null, 1, target)

        val colorBlendConfig = materialConfig.javaClass.getField("mColorBlendConfig").get(materialConfig)
        if (colorBlendConfig != null) {
            val colors = colorBlendConfig.javaClass.getField("blendColors").get(colorBlendConfig) as? IntArray
            val modes = colorBlendConfig.javaClass.getField("blendModes").get(colorBlendConfig) as? IntArray
            if (colors != null && modes != null) {
                blurUtilsClass.getMethod(
                    "setBackgroundBlendConfig",
                    View::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                ).invoke(null, target, colors, modes)
            }
        }

        if (useBloomStroke) {
            runCatching {
                val bloomConfig = materialConfig.javaClass.getField("mBloomStrokeConfig").get(materialConfig)
                if (bloomConfig != null) {
                    val bloomUtilsClass = Class.forName("miuix.core.util.HyperBloomStrokeUtils", false, cl)
                    val bloomConfigClass = Class.forName("miuix.core.util.MaterialConfig\$BloomStrokeConfig", false, cl)
                    bloomUtilsClass.getMethod("setBloomStrokeConfig", View::class.java, bloomConfigClass)
                        .invoke(null, target, bloomConfig)
                }
            }
        }

        // ViewRootImpl is only available once attached; setMiBlurWinType(1) + re-sync blur state here.
        target.addOnAttachStateChangeListener(
            object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    runCatching {
                        setMiBlurWinType.invoke(null, v)
                        setPassWindowBlur.invoke(null, v, true)
                        setBgOnlyBlur.invoke(null, blurRadiusPx, v)
                        setViewBlurMode.invoke(null, 1, v)
                    }
                    v.removeOnAttachStateChangeListener(this)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            },
        )

        "miuix_${material.name.lowercase()}(isLight=$isLight,radiusPx=$blurRadiusPx)"
    }.getOrNull()

    /**
     * Direct `android.view.View` fallback in case `miuix.theme.token.*` classes differ across ROM builds.
     */
    private fun applyDirectViewBlurFallback(
        target: View,
        pillDrawable: GradientDrawable,
        material: OverlayMaterial,
        isLight: Boolean,
    ): String? = runCatching {
        val blurRadiusPx = ((if (material == OverlayMaterial.SOFT_LIGHT_GLASS) 60f else 68f) * density)
            .roundToInt()
            .coerceIn(0, 400)

        val setPass = View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType)
        val setBgMode = View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType)
        val setBgRadius = View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
        val setViewMode = View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType)
        val setWinType = View::class.java.getMethod("setMiBlurWinType", Int::class.javaPrimitiveType)

        pillDrawable.alpha = 0
        target.elevation = 0f

        setPass.invoke(target, true)
        setBgMode.invoke(target, 1)
        setBgRadius.invoke(target, blurRadiusPx)
        setViewMode.invoke(target, 1)

        runCatching {
            View::class.java.getMethod("clearMiBackgroundBlendColor").invoke(target)
            val addBlend = View::class.java.getMethod(
                "addMiBackgroundBlendColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            if (isLight) {
                addBlend.invoke(target, Color.argb(156, 224, 232, 246), 21)
                addBlend.invoke(target, Color.argb(68, 255, 255, 255), 106)
            } else {
                addBlend.invoke(target, Color.argb(148, 122, 134, 158), 21)
                addBlend.invoke(target, Color.argb(96, 54, 62, 78), 106)
            }
        }

        target.addOnAttachStateChangeListener(
            object : OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    runCatching {
                        setWinType.invoke(v, 1)
                        setPass.invoke(v, true)
                        setBgMode.invoke(v, 1)
                        setBgRadius.invoke(v, blurRadiusPx)
                        setViewMode.invoke(v, 1)
                    }
                    v.removeOnAttachStateChangeListener(this)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            },
        )

        "view_blur_fallback(material=$material,isLight=$isLight,radiusPx=$blurRadiusPx)"
    }.getOrNull()

    private fun createFallbackGlassPillDrawable(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(
            if (isDark) Color.argb(232, 64, 72, 88) else Color.argb(236, 224, 231, 244),
        )
    }

    private fun dp(value: Float): Int = dpF(value).roundToInt().coerceAtLeast(1)

    private fun dpF(value: Float): Float = value * density * scale

    private data class MaterialPreset(
        val lightBlendName: String,
        val darkBlendName: String,
        val blurRadiusDp: Int,
        val useBloomStroke: Boolean,
    )

    private data class MaterialApplyResult(
        val liveBlurActive: Boolean,
        val summary: String,
    )

    /**
     * Draws optical glass finishing layers directly above RenderNode's live cross-window blur
     * and below child app icons:
     * - Tonal pearl-silver / smoky-slate separation veil (so glass never melts into `#FFFFFF` or `#000000`).
     * - Directional curved-glass specular highlight in [OverlayMaterial.SOFT_LIGHT_GLASS].
     * - Crisp dual-edge glass bezel (outer framing hairline + inner bright catchlight rim).
     */
    private class CapsuleSurfaceLayout(
        context: Context,
        private val cornerRadiusPx: Float,
        private val material: OverlayMaterial,
        private val isDark: Boolean,
        private val dpToPx: (Float) -> Float,
    ) : LinearLayout(context) {

        var liveBlurActive: Boolean = false

        private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val outerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(if (isDark) 1.0f else 1.15f).coerceAtLeast(1f)
        }
        private val innerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(if (material == OverlayMaterial.SOFT_LIGHT_GLASS) 1.35f else 1.15f)
                .coerceAtLeast(1f)
        }

        private val boundsRect = RectF()
        private val outerStrokeRect = RectF()
        private val innerRimRect = RectF()
        private val surfacePath = Path()
        private val outerStrokePath = Path()
        private val innerRimPath = Path()

        init {
            setWillNotDraw(false)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return

            val wf = w.toFloat()
            val hf = h.toFloat()
            boundsRect.set(0f, 0f, wf, hf)
            buildSmoothRectPath(context, surfacePath, boundsRect, cornerRadiusPx)

            val outerInset = outerStrokePaint.strokeWidth * 0.5f
            outerStrokeRect.set(outerInset, outerInset, wf - outerInset, hf - outerInset)
            val outerRadius = (cornerRadiusPx - outerInset).coerceAtLeast(0f)
            buildSmoothRectPath(context, outerStrokePath, outerStrokeRect, outerRadius)

            val innerInset = outerStrokePaint.strokeWidth + innerRimPaint.strokeWidth * 0.55f
            innerRimRect.set(innerInset, innerInset, wf - innerInset, hf - innerInset)
            val innerRadius = (cornerRadiusPx - innerInset).coerceAtLeast(0f)
            buildSmoothRectPath(context, innerRimPath, innerRimRect, innerRadius)

            configureShaders(wf, hf)
        }

        private fun configureShaders(wf: Float, hf: Float) {
            val isSoftGlass = material == OverlayMaterial.SOFT_LIGHT_GLASS
            if (isDark) {
                veilPaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    hf,
                    if (isSoftGlass) {
                        intArrayOf(
                            Color.argb(114, 126, 140, 166),
                            Color.argb(88, 90, 102, 124),
                            Color.argb(106, 102, 114, 138),
                        )
                    } else {
                        intArrayOf(
                            Color.argb(144, 108, 118, 138),
                            Color.argb(132, 88, 98, 116),
                        )
                    },
                    if (isSoftGlass) floatArrayOf(0f, 0.52f, 1f) else floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                sheenPaint.shader = LinearGradient(
                    0f,
                    0f,
                    wf * 0.85f,
                    hf * 0.42f,
                    intArrayOf(
                        Color.argb(72, 255, 255, 255),
                        Color.argb(24, 224, 236, 255),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.48f, 1f),
                    Shader.TileMode.CLAMP,
                )
                outerStrokePaint.shader = null
                outerStrokePaint.color = Color.argb(156, 0, 0, 0)
                innerRimPaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    hf,
                    if (isSoftGlass) {
                        intArrayOf(
                            Color.argb(182, 255, 255, 255),
                            Color.argb(84, 226, 236, 255),
                            Color.argb(132, 255, 255, 255),
                        )
                    } else {
                        intArrayOf(
                            Color.argb(118, 255, 255, 255),
                            Color.argb(68, 226, 236, 255),
                        )
                    },
                    if (isSoftGlass) floatArrayOf(0f, 0.50f, 1f) else floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            } else {
                veilPaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    hf,
                    if (isSoftGlass) {
                        intArrayOf(
                            Color.argb(122, 214, 224, 242),
                            Color.argb(96, 204, 216, 236),
                            Color.argb(118, 212, 222, 240),
                        )
                    } else {
                        intArrayOf(
                            Color.argb(158, 204, 214, 232),
                            Color.argb(148, 196, 206, 226),
                        )
                    },
                    if (isSoftGlass) floatArrayOf(0f, 0.52f, 1f) else floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                sheenPaint.shader = LinearGradient(
                    0f,
                    0f,
                    wf * 0.85f,
                    hf * 0.42f,
                    intArrayOf(
                        Color.argb(142, 255, 255, 255),
                        Color.argb(46, 255, 255, 255),
                        Color.TRANSPARENT,
                    ),
                    floatArrayOf(0f, 0.46f, 1f),
                    Shader.TileMode.CLAMP,
                )
                outerStrokePaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    hf,
                    if (isSoftGlass) {
                        intArrayOf(
                            Color.argb(58, 15, 23, 42),
                            Color.argb(72, 15, 23, 42),
                            Color.argb(92, 15, 23, 42),
                        )
                    } else {
                        intArrayOf(
                            Color.argb(68, 15, 23, 42),
                            Color.argb(94, 15, 23, 42),
                        )
                    },
                    if (isSoftGlass) floatArrayOf(0f, 0.55f, 1f) else floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
                innerRimPaint.shader = LinearGradient(
                    0f,
                    0f,
                    0f,
                    hf,
                    if (isSoftGlass) {
                        intArrayOf(
                            Color.argb(236, 255, 255, 255),
                            Color.argb(136, 255, 255, 255),
                            Color.argb(198, 255, 255, 255),
                        )
                    } else {
                        intArrayOf(
                            Color.argb(168, 255, 255, 255),
                            Color.argb(76, 255, 255, 255),
                        )
                    },
                    if (isSoftGlass) floatArrayOf(0f, 0.50f, 1f) else floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (boundsRect.isEmpty) return

            if (liveBlurActive) {
                canvas.drawPath(surfacePath, veilPaint)
            }
            if (material == OverlayMaterial.SOFT_LIGHT_GLASS) {
                canvas.drawPath(surfacePath, sheenPaint)
            }

            canvas.drawPath(innerRimPath, innerRimPaint)
            canvas.drawPath(outerStrokePath, outerStrokePaint)
        }
    }

    companion object {
        /**
         * Exact corner-radius-to-width ratio measured from HyperOS 4's native volume slider
         * (`25.5px / 69.2px = 0.368`, paired with Xiaomi's G2 continuous smooth corner).
         */
        internal const val VOLUME_CORNER_TO_WIDTH_RATIO = 0.368f

        private fun enableViewSmoothCorner(target: View) {
            runCatching {
                val helperClass = Class.forName(
                    "miuix.smooth.SmoothCornerHelper",
                    false,
                    target.context.classLoader,
                )
                helperClass.getMethod(
                    "setViewSmoothCornerEnable",
                    View::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(null, target, true)
            }
            runCatching {
                View::class.java
                    .getMethod("setSmoothCornerEnabled", Boolean::class.javaPrimitiveType)
                    .invoke(target, true)
            }
            target.addOnAttachStateChangeListener(
                object : OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        runCatching {
                            View::class.java
                                .getMethod("setSmoothCornerEnabled", Boolean::class.javaPrimitiveType)
                                .invoke(v, true)
                        }
                        v.removeOnAttachStateChangeListener(this)
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                },
            )
        }

        /**
         * Builds HyperOS 4's exact G2 continuous smooth-corner path via SystemUI's own
         * `miuix.smooth.internal.SmoothDrawHelper`, with an analytical G2 superellipse fallback.
         */
        private fun buildSmoothRectPath(
            context: Context,
            outPath: Path,
            rect: RectF,
            radiusPx: Float,
        ) {
            outPath.reset()
            if (rect.isEmpty) return
            val clampedRadius = min(radiusPx, min(rect.width(), rect.height()) * 0.5f).coerceAtLeast(0f)
            if (clampedRadius <= 0f) {
                outPath.addRect(rect, Path.Direction.CW)
                return
            }

            val usedMiuix = runCatching {
                val helperClass = Class.forName(
                    "miuix.smooth.internal.SmoothDrawHelper",
                    false,
                    context.classLoader,
                )
                val helper = helperClass.getConstructor().newInstance()
                val method = helperClass.getMethod(
                    "getSmoothPathFromProvider",
                    Path::class.java,
                    RectF::class.java,
                    FloatArray::class.java,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                )
                method.invoke(helper, outPath, rect, null, clampedRadius, 0f, 0f)
                !outPath.isEmpty
            }.getOrDefault(false)

            if (!usedMiuix) {
                buildAnalyticalSmoothRectPath(outPath, rect, clampedRadius)
            }
        }

        private fun buildAnalyticalSmoothRectPath(
            outPath: Path,
            rect: RectF,
            radiusPx: Float,
        ) {
            val reach = min(radiusPx * 1.165f, min(rect.width(), rect.height()) * 0.5f)
            val exp = 2.0 / 2.40
            val steps = 12

            fun cornerPoints(
                cx: Float,
                cy: Float,
                sx: Float,
                sy: Float,
                startAngle: Double,
                endAngle: Double,
                moveFirst: Boolean,
            ) {
                for (i in 0..steps) {
                    val t = startAngle + (endAngle - startAngle) * (i.toDouble() / steps)
                    val px = cx + sx * reach * cos(t).pow(exp).toFloat()
                    val py = cy + sy * reach * sin(t).pow(exp).toFloat()
                    if (i == 0 && moveFirst) {
                        outPath.moveTo(px, py)
                    } else {
                        outPath.lineTo(px, py)
                    }
                }
            }

            val halfPi = Math.PI * 0.5
            // Top-left (left -> top), Top-right (top -> right), Bottom-right (right -> bottom), Bottom-left (bottom -> left)
            cornerPoints(rect.left + reach, rect.top + reach, -1f, -1f, 0.0, halfPi, moveFirst = true)
            cornerPoints(rect.right - reach, rect.top + reach, 1f, -1f, halfPi, 0.0, moveFirst = false)
            cornerPoints(rect.right - reach, rect.bottom - reach, 1f, 1f, 0.0, halfPi, moveFirst = false)
            cornerPoints(rect.left + reach, rect.bottom - reach, -1f, 1f, halfPi, 0.0, moveFirst = false)
            outPath.close()
        }
    }
}
