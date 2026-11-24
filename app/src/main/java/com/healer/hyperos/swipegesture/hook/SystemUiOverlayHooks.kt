package com.healer.hyperos.swipegesture.hook

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.healer.hyperos.swipegesture.ipc.OverlayBinderHost
import com.healer.hyperos.swipegesture.ipc.OverlayChannelContract
import com.healer.hyperos.swipegesture.ipc.OverlayShowPayload
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `com.android.systemui` overlay host & WM Shell predictive-back hook.
 *
 * Hosts the opposite-edge frosted-glass capsule menu safely outside `system_server`:
 * - Always resident (immune to HyperOS Millet freezing).
 * - Never requires user-granted `SYSTEM_ALERT_WINDOW`.
 * - Receives non-blocking oneway Binder commands from `system_server`.
 */
internal object SystemUiOverlayHooks {

    private const val TAG = SwipeGestureModuleEntry.TAG
    private val installed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var moduleRef: XposedModule? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var backSuppressedUntilUptimeMs: Long = 0L

    private var activeOverlayView: OppositeEdgeCapsuleView? = null

    private val overlayBinderHost = OverlayBinderHost(
        object : OverlayBinderHost.Listener {
            override fun onShow(payload: OverlayShowPayload) {
                mainHandler.post { showOverlayOnMain(payload) }
            }

            override fun onUpdateSelection(selectedIndex: Int, hitBoundary: Boolean) {
                mainHandler.post { updateSelectionOnMain(selectedIndex, hitBoundary) }
            }

            override fun onHide(cancelled: Boolean) {
                mainHandler.post { hideOverlayOnMain(cancelled) }
            }

            override fun onSuppressBackUntil(untilUptimeMs: Long) {
                backSuppressedUntilUptimeMs = untilUptimeMs
            }
        },
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        moduleRef = module

        hookApplicationCreate(module, classLoader)
        // TODO(HyperOS-4.0.6.0): Inspect MiuiSystemUI.apk WM Shell BackAnimationController
        // and cancel predictive-back commit when isBackSuppressedNow() is true.
    }

    fun isBackSuppressedNow(): Boolean = SystemClock.uptimeMillis() <= backSuppressedUntilUptimeMs

    private fun hookApplicationCreate(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val onCreate = Application::class.java.getDeclaredMethod("onCreate")
            module.hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val app = chain.thisObject as? Application
                    if (app != null && appContext == null) {
                        appContext = app.applicationContext
                        registerHostWithSystemServer(app.applicationContext)
                    }
                    result
                }
            module.log(Log.INFO, TAG, "SystemUI Application.onCreate hook installed")
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "Failed to hook SystemUI Application.onCreate", err)
        }
        @Suppress("UNUSED_EXPRESSION")
        classLoader
    }

    private fun registerHostWithSystemServer(context: Context) {
        runCatching {
            val intent = Intent(OverlayChannelContract.ACTION_REGISTER_OVERLAY_HOST).apply {
                setPackage("android")
                putExtras(
                    Bundle().apply {
                        putBinder(OverlayChannelContract.EXTRA_OVERLAY_BINDER, overlayBinderHost)
                    },
                )
            }
            context.sendBroadcast(intent)
            moduleRef?.log(Log.INFO, TAG, "Dispatched SystemUI overlay Binder token to system_server")
        }.onFailure { err ->
            moduleRef?.log(Log.WARN, TAG, "Failed to dispatch overlay Binder token", err)
        }
    }

    private fun showOverlayOnMain(payload: OverlayShowPayload) {
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        hideOverlayOnMain(cancelled = false)

        runCatching {
            val view = OppositeEdgeCapsuleView(ctx, payload)
            val dm = ctx.resources.displayMetrics
            val marginX = (dm.widthPixels * 0.036f * payload.scale).toInt().coerceAtLeast(8)
            val marginY = (dm.heightPixels * 0.03f).toInt()
            val estimatedHeight = view.estimatedHeightPx
            val topY = (payload.anchorRawY.toInt() - estimatedHeight / 2)
                .coerceIn(marginY, (dm.heightPixels - estimatedHeight - marginY).coerceAtLeast(marginY))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = if (payload.fromLeft) {
                    Gravity.END or Gravity.TOP
                } else {
                    Gravity.START or Gravity.TOP
                }
                x = marginX
                y = topY
                title = "HyperSwipeGestureCapsule"
            }

            wm.addView(view, params)
            activeOverlayView = view
            view.animateEnter()
            performHaptic(ctx, heavy = false)
        }.onFailure { err ->
            moduleRef?.log(Log.WARN, TAG, "Failed to show SystemUI overlay", err)
        }
    }

    private fun updateSelectionOnMain(selectedIndex: Int, hitBoundary: Boolean) {
        val ctx = appContext ?: return
        activeOverlayView?.updateSelection(selectedIndex)
        performHaptic(ctx, heavy = hitBoundary)
    }

    private fun hideOverlayOnMain(cancelled: Boolean) {
        val view = activeOverlayView ?: return
        activeOverlayView = null
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching {
            wm.removeViewImmediate(view)
        }.onFailure { err ->
            moduleRef?.log(Log.WARN, TAG, "Failed to remove SystemUI overlay (cancelled=$cancelled)", err)
        }
    }

    @Suppress("DEPRECATION")
    private fun performHaptic(context: Context, heavy: Boolean) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) return
            val effectId = if (heavy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.EFFECT_HEAVY_CLICK
            } else {
                VibrationEffect.EFFECT_TICK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
            }
        }
    }
}
