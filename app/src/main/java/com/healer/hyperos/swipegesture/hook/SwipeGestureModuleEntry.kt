package com.healer.hyperos.swipegesture.hook

import android.os.Build
import android.util.Log
import com.healer.hyperos.swipegesture.BuildConfig
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Modern libxposed API 102 entry targeting HyperOS 4 (Android 17 / SDK 37).
 *
 * Process responsibilities:
 * - `system` (system_server): ultra-thin input listener, pure state machine, back suppression,
 *   recent task lookup & moveTaskToFront. Zero UI views, zero blocking IPC.
 * - `com.android.systemui`: persistent overlay renderer (opposite-edge frosted-glass capsule)
 *   driven over oneway Binder, plus shade/control-center state bridge.
 */
class SwipeGestureModuleEntry : XposedModule() {

    private val systemServerInstalled = AtomicBoolean(false)
    private val systemUiInstalled = AtomicBoolean(false)

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "Module loaded process=${param.processName} systemServer=${param.isSystemServer} " +
                "api=$apiVersion framework=$frameworkName",
        )
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        if (Build.VERSION.SDK_INT < MIN_HYPEROS4_SDK) {
            log(Log.INFO, TAG, "Skipping system_server hooks on sdk=${Build.VERSION.SDK_INT}")
            return
        }
        RuntimeSettingsHolder.init(this)
        if (systemServerInstalled.compareAndSet(false, true)) {
            SystemServerGestureHooks.install(this, param.classLoader)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (Build.VERSION.SDK_INT < MIN_HYPEROS4_SDK) return
        val packageName = param.packageName
        if (packageName == BuildConfig.APPLICATION_ID) return
        if (packageName != PKG_SYSTEM_UI) return

        RuntimeSettingsHolder.init(this)
        if (systemUiInstalled.compareAndSet(false, true)) {
            log(Log.INFO, TAG, "Initializing SystemUI overlay host hooks (PackageReady)")
            SystemUiOverlayHooks.install(this, param.classLoader)
        }
    }

    companion object {
        const val TAG = "HyperSwipeGesture"
        const val PKG_SYSTEM_FRAMEWORK = "system"
        const val PKG_SYSTEM_UI = "com.android.systemui"
        const val PKG_LAUNCHER = "com.miui.home"
        private const val MIN_HYPEROS4_SDK = 37
    }
}
