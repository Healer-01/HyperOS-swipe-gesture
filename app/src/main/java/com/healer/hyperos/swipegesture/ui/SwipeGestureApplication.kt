package com.healer.hyperos.swipegesture.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.healer.hyperos.swipegesture.hook.SwipeGestureModuleEntry
import com.healer.hyperos.swipegesture.policy.SettingsContract
import com.healer.hyperos.swipegesture.policy.SwipeGestureSettings
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FrameworkSnapshot(
    val connected: Boolean = false,
    val apiVersion: Int = 0,
    val frameworkName: String = "",
    val frameworkVersion: String = "",
    val scope: Set<String> = emptySet(),
    val statusMessage: String = "",
) {
    val apiCompatible: Boolean
        get() = connected && apiVersion >= MIN_SUPPORTED_API

    val systemFrameworkInScope: Boolean
        get() = SwipeGestureModuleEntry.PKG_SYSTEM_FRAMEWORK in scope || "android" in scope

    val systemUiInScope: Boolean
        get() = SwipeGestureModuleEntry.PKG_SYSTEM_UI in scope

    val fullyScoped: Boolean
        get() = apiCompatible && systemFrameworkInScope && systemUiInScope

    companion object {
        const val MIN_SUPPORTED_API = 102
    }
}

class SwipeGestureApplication : Application(), XposedServiceHelper.OnServiceListener {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.initLocal(this)
        runCatching {
            XposedServiceHelper.registerListener(this)
        }
    }

    override fun onServiceBind(service: XposedService) {
        SettingsRepository.onXposedServiceBound(service)
    }

    override fun onServiceDied(service: XposedService) {
        SettingsRepository.onXposedServiceDied()
    }
}

object SettingsRepository {
    private val _settings = MutableStateFlow(SwipeGestureSettings())
    val settings: StateFlow<SwipeGestureSettings> = _settings.asStateFlow()

    private val _framework = MutableStateFlow(FrameworkSnapshot())
    val framework: StateFlow<FrameworkSnapshot> = _framework.asStateFlow()

    @Volatile
    private var localPrefs: SharedPreferences? = null

    @Volatile
    private var remotePrefs: SharedPreferences? = null

    @Volatile
    private var currentService: XposedService? = null

    @Synchronized
    fun initLocal(context: Context) {
        if (localPrefs == null) {
            val prefs = context.getSharedPreferences(SettingsContract.PREFS_NAME, Context.MODE_PRIVATE)
            localPrefs = prefs
            _settings.value = SettingsContract.read(prefs)
        }
    }

    @Synchronized
    fun onXposedServiceBound(service: XposedService) {
        currentService = service
        val remote = runCatching {
            service.getRemotePreferences(SettingsContract.PREFS_NAME)
        }.getOrNull()
        remotePrefs = remote

        if (remote != null) {
            if (remote.all.isEmpty()) {
                SettingsContract.write(remote, _settings.value)
            } else {
                val loaded = SettingsContract.read(remote)
                _settings.value = loaded
                localPrefs?.let { SettingsContract.write(it, loaded) }
            }
        }

        _framework.value = FrameworkSnapshot(
            connected = true,
            apiVersion = runCatching { service.apiVersion }.getOrDefault(0),
            frameworkName = runCatching { service.frameworkName }.getOrDefault("libxposed"),
            frameworkVersion = runCatching { service.frameworkVersion }.getOrDefault(""),
            scope = runCatching { service.scope.toSet() }.getOrDefault(emptySet()),
        )
    }

    @Synchronized
    fun onXposedServiceDied() {
        currentService = null
        remotePrefs = null
        _framework.value = FrameworkSnapshot()
    }

    @Synchronized
    fun update(transform: (SwipeGestureSettings) -> SwipeGestureSettings) {
        val next = transform(_settings.value).sanitized()
        _settings.value = next
        localPrefs?.let { SettingsContract.write(it, next) }
        remotePrefs?.let { SettingsContract.write(it, next) }
    }

    fun requestMissingScope() {
        val service = currentService ?: return
        val targets = listOf(
            SwipeGestureModuleEntry.PKG_SYSTEM_FRAMEWORK,
            SwipeGestureModuleEntry.PKG_SYSTEM_UI,
        )
        runCatching {
            service.requestScope(
                targets,
                object : XposedService.OnScopeEventListener {
                    override fun onScopeRequestApproved(approved: MutableList<String>) {
                        val updatedScope = runCatching { service.scope.toSet() }
                            .getOrDefault(approved.toSet())
                        _framework.value = _framework.value.copy(
                            scope = updatedScope,
                            statusMessage = "已授权作用域：${approved.joinToString(", ")}",
                        )
                    }

                    override fun onScopeRequestFailed(message: String) {
                        _framework.value = _framework.value.copy(
                            statusMessage = "作用域申请失败：$message",
                        )
                    }
                },
            )
        }.onFailure {
            _framework.value = _framework.value.copy(
                statusMessage = "当前框架不支持自动申请作用域，请在 LSPosed 管理器手动勾选系统框架与系统界面",
            )
        }
    }
}
