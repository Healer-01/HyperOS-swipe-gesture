package com.healer.hyperos.swipegesture.hook

import android.content.SharedPreferences
import android.util.Log
import com.healer.hyperos.swipegesture.policy.SettingsContract
import com.healer.hyperos.swipegesture.policy.SwipeGestureSettings
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zero-blocking RemotePreferences cache shared inside hooked system processes.
 *
 * Unlike ContentProvider queries (which can deadlock or watchdog system_server when the module app
 * is frozen by HyperOS Millet), libxposed RemotePreferences are pushed in-memory by the framework.
 */
internal object RuntimeSettingsHolder {

    @Volatile
    var current: SwipeGestureSettings = SwipeGestureSettings()
        private set

    private val bound = AtomicBoolean(false)
    private val listenerRegistered = AtomicBoolean(false)

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, _ ->
        reload(prefs)
    }

    val isBound: Boolean
        get() = bound.get()

    fun ensureBound(module: XposedModule?) {
        if (!bound.get() && module != null) {
            init(module)
        } else {
            refresh()
        }
    }

    /**
     * Re-reads the in-memory `RemotePreferences` map directly (zero IPC / zero disk I/O) so
     * hooked processes never rely on a missed `OnSharedPreferenceChangeListener` callback.
     */
    fun refresh(): SwipeGestureSettings {
        remotePrefs?.let { reload(it) }
        return current
    }

    fun init(module: XposedModule) {
        if (!bound.compareAndSet(false, true)) {
            remotePrefs?.let { reload(it) }
            return
        }
        try {
            val prefs = module.getRemotePreferences(SettingsContract.PREFS_NAME)
            remotePrefs = prefs
            reload(prefs)
            if (listenerRegistered.compareAndSet(false, true)) {
                prefs.registerOnSharedPreferenceChangeListener(changeListener)
            }
        } catch (t: Throwable) {
            bound.set(false)
            module.log(
                Log.WARN,
                SwipeGestureModuleEntry.TAG,
                "Failed to bind RemotePreferences; using defaults",
                t,
            )
        }
    }

    private fun reload(prefs: SharedPreferences) {
        runCatching {
            current = SettingsContract.read(prefs)
        }
    }
}
