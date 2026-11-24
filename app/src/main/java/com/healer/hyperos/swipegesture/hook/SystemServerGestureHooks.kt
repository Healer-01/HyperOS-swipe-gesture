package com.healer.hyperos.swipegesture.hook

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import com.healer.hyperos.swipegesture.ipc.CandidateApp
import com.healer.hyperos.swipegesture.ipc.OverlayBinderClient
import com.healer.hyperos.swipegesture.ipc.OverlayChannelContract
import com.healer.hyperos.swipegesture.ipc.OverlayShowPayload
import com.healer.hyperos.swipegesture.ipc.SystemServerControlHost
import com.healer.hyperos.swipegesture.policy.SwipeGestureSettings
import com.healer.hyperos.swipegesture.policy.SwipeGestureStateMachine
import com.healer.hyperos.swipegesture.policy.SwitchMode
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Ultra-thin `system_server` controller verified against Xiaomi 17 HyperOS 4.0.6.0 (`SDK 37`).
 *
 * Verified hook topology:
 * - Input channel: `com.miui.server.input.gesture.MiuiGestureMonitor` (`[Gesture Monitor] miui-gesture`),
 *   which sits above `GestureStubLeft`/`GestureStubRight` in Z-order and runs on a dedicated real-time
 *   `HandlerThread` (avoiding the `DO_NOT_PILFER` `PointerEventDispatcherOverlay0` on `android.ui`).
 * - Back cancellation & suppression:
 *   1. `MiuiGestureMonitor.pilferPointers()` cancels `GestureStubLeft`/`GestureStubRight` (`libapp_launcher.so`).
 *   2. `BackNavigationController.startBackNavigation` wraps `BackNavigationInfo.mOnBackInvokedCallback`
 *      and converts suppressed `onBackInvoked` calls into `onBackCancelled`, plus `cancelBackNavigating`
 *      on `NavigationMonitor` and `onBackNavigationDone` `NavigationFinished=false` guard.
 *   3. `InputManagerService.injectInputEventToTarget` absorbs fallback `KEYCODE_BACK` injections.
 * - Zero UI views, zero `createPackageContext`, zero blocking IPC on input threads.
 */
internal object SystemServerGestureHooks {

    private const val TAG = SwipeGestureModuleEntry.TAG
    private const val WINDOWING_MODE_FREEFORM = 5
    private const val WINDOWING_MODE_PC_FREEFORM = 100
    private const val POST_UP_BACK_SUPPRESSION_GRACE_MS = 450L
    private const val MIN_EDGE_INSET_PX = 68
    private const val EDGE_INSET_DP = 28f

    private val installed = AtomicBoolean(false)
    private val monitorInstalled = AtomicBoolean(false)
    private val receiverRegistered = AtomicBoolean(false)
    private val gestureSeq = AtomicInteger(0)

    private val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperSwipeGesture-Worker").apply { isDaemon = true }
    }

    private val stateMachine = SwipeGestureStateMachine()

    @Volatile
    private var moduleRef: XposedModule? = null

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var wmsHandler: Handler? = null

    @Volatile
    private var wmsGlobalLock: Any? = null

    @Volatile
    private var wmsPolicy: Any? = null

    @Volatile
    private var imeVisibleHeightField: Field? = null

    @Volatile
    private var gestureHandler: Handler? = null

    @Volatile
    private var overlayClient: OverlayBinderClient? = null

    @Volatile
    private var systemUiUid: Int = -1

    @Volatile
    private var systemControlBound: Boolean = false

    @Volatile
    private var systemUiBlockingFromOverlay: Boolean = false

    @Volatile
    private var statusBarPanelRevealed: Boolean = false

    @Volatile
    private var backSuppressedUntilUptimeMs: Long = 0L

    @Volatile
    private var activeCandidates: List<CandidateApp> = emptyList()

    @Volatile
    private var prefetchedResult: PrefetchedCandidates? = null

    private data class PrefetchedCandidates(
        val seq: Int,
        val items: List<CandidateApp>,
    )

    @Volatile
    private var miuiGestureMonitorInstance: Any? = null

    @Volatile
    private var miuiPilferPointersMethod: Method? = null

    @Volatile
    private var backNavControllerRef: Any? = null

    private var pauseRunnable: Runnable? = null

    private var cachedScreenWidth: Int = 1220
    private var cachedScreenHeight: Int = 2656
    private var cachedDensity: Float = 3.25f

    private val systemControlHost = SystemServerControlHost(
        allowedUidProvider = { resolveSystemUiUid() },
        onSystemUiBlockingChanged = { blocked ->
            systemControlBound = true
            systemUiBlockingFromOverlay = blocked
        },
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        moduleRef = module

        hookWindowManagerSystemReady(module, classLoader)
        hookBackNavigationController(module, classLoader)
        hookInputManagerBackInjection(module, classLoader)
        hookStatusBarPanelState(module, classLoader)

        module.log(Log.INFO, TAG, "SystemServerGestureHooks installed for HyperOS 4.0.6.0")
    }

    private fun hookWindowManagerSystemReady(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val wmsClass = Class.forName("com.android.server.wm.WindowManagerService", false, classLoader)
            val systemReadyMethod = wmsClass.getDeclaredMethod("systemReady")
            module.hook(systemReadyMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    val wms = chain.thisObject
                    if (wms != null) {
                        bindWindowManagerRefs(module, wms, classLoader)
                    }
                    result
                }
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "Failed to hook WindowManagerService.systemReady", err)
        }
    }

    private fun bindWindowManagerRefs(module: XposedModule, wms: Any, classLoader: ClassLoader) {
        RuntimeSettingsHolder.ensureBound(module)
        val ctx = runCatching { findField(wms.javaClass, "mContext")?.get(wms) as? Context }.getOrNull()
        if (ctx != null) {
            systemContext = ctx
            ensureOverlayHostReceiver(ctx)
            workerExecutor.execute { resolveSystemUiUid() }
            installGestureMonitor(module, ctx, classLoader)
        }
        wmsHandler = runCatching { findField(wms.javaClass, "mH")?.get(wms) as? Handler }.getOrNull()
        wmsGlobalLock = runCatching { findField(wms.javaClass, "mGlobalLock")?.get(wms) }.getOrNull()
        val policy = runCatching { findField(wms.javaClass, "mPolicy")?.get(wms) }.getOrNull()
        wmsPolicy = policy
        if (policy != null && imeVisibleHeightField == null) {
            imeVisibleHeightField = findField(policy.javaClass, "mInputMethodWindowVisibleHeight")
        }
        if (backNavControllerRef == null) {
            backNavControllerRef = runCatching {
                val atm = findField(wms.javaClass, "mAtmService")?.get(wms) ?: return@runCatching null
                findField(atm.javaClass, "mBackNavigationController")?.get(atm)
            }.getOrNull()
        }
    }

    private fun hookBackNavigationController(module: XposedModule, classLoader: ClassLoader) {
        val reflection = BackCallbackReflection.resolve(classLoader)
        runCatching {
            val bncClass = Class.forName("com.android.server.wm.BackNavigationController", false, classLoader)
            val remoteCallbackClass = Class.forName("android.os.RemoteCallback", false, classLoader)
            val adapterClass = Class.forName("android.window.BackAnimationAdapter", false, classLoader)
            val startMethod = bncClass.getDeclaredMethod(
                "startBackNavigation",
                remoteCallbackClass,
                adapterClass,
            )
            module.hook(startMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    backNavControllerRef = chain.thisObject
                    if (isBackSuppressedNow()) {
                        logTrace { "Blocked BackNavigationController.startBackNavigation during suppression window" }
                        return@intercept null
                    }
                    val info = chain.proceed() ?: return@intercept null
                    if (reflection != null) {
                        wrapBackNavigationInfoCallback(info, reflection)
                    }
                    info
                }

            val doneMethod = bncClass.getDeclaredMethod(
                "onBackNavigationDone",
                Bundle::class.java,
                Int::class.javaPrimitiveType,
            )
            module.hook(doneMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    backNavControllerRef = chain.thisObject
                    val resultBundle = chain.args.getOrNull(0) as? Bundle
                    if (isBackSuppressedNow() &&
                        resultBundle != null &&
                        resultBundle.getBoolean("NavigationFinished", false)
                    ) {
                        resultBundle.putBoolean("NavigationFinished", false)
                        logTrace { "Neutralized BackNavigationController.onBackNavigationDone NavigationFinished=true" }
                    }
                    chain.proceed()
                }
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "Failed to hook BackNavigationController", err)
        }
    }

    private fun wrapBackNavigationInfoCallback(info: Any, reflection: BackCallbackReflection) {
        runCatching {
            val original = reflection.infoCallbackField.get(info) as? IInterface ?: return
            val realBinder = original.asBinder() ?: return
            if (realBinder is BackCallbackSuppressionBinder) return

            val proxyBinder = BackCallbackSuppressionBinder(
                realBinder = realBinder,
                reflection = reflection,
                isSuppressed = ::isBackSuppressedNow,
                logTrace = ::logTrace,
            )
            val wrapped = reflection.stubAsInterfaceMethod.invoke(null, proxyBinder) ?: return
            reflection.infoCallbackField.set(info, wrapped)
        }
    }

    private fun hookInputManagerBackInjection(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val imsClass = Class.forName("com.android.server.input.InputManagerService", false, classLoader)
            val injectMethod = imsClass.getDeclaredMethod(
                "injectInputEventToTarget",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Array<String>::class.java,
            )
            module.hook(injectMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val event = chain.args.getOrNull(0)
                    if (event is KeyEvent &&
                        event.keyCode == KeyEvent.KEYCODE_BACK &&
                        isBackSuppressedNow()
                    ) {
                        logTrace { "Absorbed injected KEYCODE_BACK action=${event.action}" }
                        return@intercept true
                    }
                    chain.proceed()
                }
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "Failed to hook InputManagerService.injectInputEventToTarget", err)
        }
    }

    private fun hookStatusBarPanelState(module: XposedModule, classLoader: ClassLoader) {
        runCatching {
            val sbmsClass = Class.forName(
                "com.android.server.statusbar.StatusBarManagerService",
                false,
                classLoader,
            )
            val revealedMethod = sbmsClass.getDeclaredMethod(
                "onPanelRevealed",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            )
            module.hook(revealedMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    statusBarPanelRevealed = true
                    chain.proceed()
                }

            val hiddenMethod = sbmsClass.getDeclaredMethod("onPanelHidden")
            module.hook(hiddenMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    statusBarPanelRevealed = false
                    chain.proceed()
                }
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "Failed to hook StatusBarManagerService panel state", err)
        }
    }

    private fun installGestureMonitor(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
    ) {
        if (!monitorInstalled.compareAndSet(false, true)) return

        val miuiInstalled = runCatching {
            val monitorClass = Class.forName(
                "com.miui.server.input.gesture.MiuiGestureMonitor",
                false,
                classLoader,
            )
            val listenerClass = Class.forName(
                "com.miui.server.input.gesture.MiuiGestureListener",
                false,
                classLoader,
            )
            val getInstance = monitorClass.getDeclaredMethod("getInstance", Context::class.java)
            val monitor = getInstance.invoke(null, context) ?: error("MiuiGestureMonitor.getInstance returned null")
            val registerMethod = monitorClass.getDeclaredMethod("registerPointerEventListener", listenerClass)
            val pilferMethod = monitorClass.getDeclaredMethod("pilferPointers")

            val listenerProxy = Proxy.newProxyInstance(
                classLoader,
                arrayOf(listenerClass),
            ) { proxy, method, args ->
                when (method.name) {
                    "onPointerEvent" -> {
                        val event = args?.getOrNull(0) as? MotionEvent
                        if (event != null) {
                            onMonitorMotionEvent(context, event)
                        }
                        null
                    }

                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "HyperSwipeGestureListener"
                    else -> null
                }
            }

            registerMethod.invoke(monitor, listenerProxy)
            miuiGestureMonitorInstance = monitor
            miuiPilferPointersMethod = pilferMethod
            module.log(Log.INFO, TAG, "Bound primary input channel to MiuiGestureMonitor")
            true
        }.onFailure { err ->
            module.log(Log.WARN, TAG, "MiuiGestureMonitor unavailable, trying InputManagerGlobal fallback", err)
        }.getOrDefault(false)

        if (!miuiInstalled) {
            installFallbackInputMonitor(module, context, classLoader)
        }
    }

    private fun installFallbackInputMonitor(
        module: XposedModule,
        context: Context,
        classLoader: ClassLoader,
    ) {
        runCatching {
            val listenerClass = Class.forName(
                "com.android.server.wm.SystemGesturesPointerEventListener",
                false,
                classLoader,
            )
            val onPointerEventMethod = listenerClass.getDeclaredMethod(
                "onPointerEvent",
                MotionEvent::class.java,
            )
            module.hook(onPointerEventMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val event = chain.args.getOrNull(0) as? MotionEvent
                    if (event != null) {
                        onMonitorMotionEvent(context, event)
                    }
                    chain.proceed()
                }
            module.log(Log.INFO, TAG, "Bound fallback SystemGesturesPointerEventListener hook")
        }.onFailure { err ->
            monitorInstalled.set(false)
            module.log(Log.ERROR, TAG, "Failed to install fallback gesture monitor", err)
        }
    }

    private fun onMonitorMotionEvent(context: Context, event: MotionEvent) {
        if ((event.source and InputDevice.SOURCE_TOUCHSCREEN) == 0 &&
            event.source != InputDevice.SOURCE_UNKNOWN
        ) {
            return
        }

        val handler = gestureHandler ?: Handler(Looper.myLooper() ?: Looper.getMainLooper()).also {
            gestureHandler = it
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                RuntimeSettingsHolder.ensureBound(moduleRef)
                refreshDisplayMetrics(context)
                val edgeInsetPx = maxOf(MIN_EDGE_INSET_PX, (EDGE_INSET_DP * cachedDensity).roundToInt())
                val imeHeightPx = readImeVisibleHeightPx()
                gestureSeq.incrementAndGet()
                prefetchedResult = null
                handlePointerDown(
                    context = context,
                    handler = handler,
                    rawX = event.rawX,
                    rawY = event.rawY,
                    screenWidth = cachedScreenWidth,
                    screenHeight = cachedScreenHeight,
                    edgeInsetPx = edgeInsetPx,
                    imeVisibleHeightPx = imeHeightPx,
                )
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                gestureSeq.incrementAndGet()
                prefetchedResult = null
                handleMultiTouchPointerDown(handler)
            }

            MotionEvent.ACTION_MOVE -> {
                handlePointerMove(
                    context = context,
                    handler = handler,
                    rawX = event.rawX,
                    rawY = event.rawY,
                )
            }

            MotionEvent.ACTION_UP -> {
                gestureSeq.incrementAndGet()
                prefetchedResult = null
                handlePointerUpOrCancel(context = context, handler = handler, cancelled = false)
            }

            MotionEvent.ACTION_CANCEL -> {
                gestureSeq.incrementAndGet()
                prefetchedResult = null
                handlePointerUpOrCancel(context = context, handler = handler, cancelled = true)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun ensureOverlayHostReceiver(context: Context) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        runCatching {
            val filter = IntentFilter(OverlayChannelContract.ACTION_REGISTER_OVERLAY_HOST)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val binder: IBinder? = intent?.extras?.getBinder(
                        OverlayChannelContract.EXTRA_OVERLAY_BINDER,
                    )
                    if (binder != null) {
                        val client = OverlayBinderClient(binder)
                        overlayClient = client
                        client.registerSystemControl(systemControlHost)
                        moduleRef?.log(Log.INFO, TAG, "Bound bidirectional oneway channel with SystemUI")
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        }.onFailure { err ->
            receiverRegistered.set(false)
            moduleRef?.log(Log.WARN, TAG, "Failed to register overlay host receiver", err)
        }
    }

    fun isBackSuppressedNow(): Boolean =
        stateMachine.isBackSuppressionLatched ||
            SystemClock.uptimeMillis() <= backSuppressedUntilUptimeMs

    fun handlePointerDown(
        context: Context,
        handler: Handler,
        rawX: Float,
        rawY: Float,
        screenWidth: Int,
        screenHeight: Int,
        edgeInsetPx: Int,
        imeVisibleHeightPx: Int = 0,
    ) {
        ensureOverlayHostReceiver(context)
        cancelPendingPause(handler)
        activeCandidates = emptyList()

        val settings = RuntimeSettingsHolder.refresh()
        if (!settings.enabled || isSystemUiBlockingGesture(context)) {
            return
        }

        val tracking = stateMachine.onDown(
            rawX = rawX,
            rawY = rawY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            edgeInsetPx = edgeInsetPx,
            settings = settings,
            imeVisibleHeightPx = imeVisibleHeightPx,
        )
        if (tracking) {
            logTrace {
                "Edge DOWN tracked rawX=$rawX rawY=$rawY fromLeft=${stateMachine.fromLeft} " +
                    "screen=${screenWidth}x$screenHeight ime=$imeVisibleHeightPx"
            }
        }
    }

    fun handlePointerMove(
        context: Context,
        handler: Handler,
        rawX: Float,
        rawY: Float,
    ) {
        val settings = RuntimeSettingsHolder.current
        when (val action = stateMachine.onMove(rawX, rawY, settings)) {
            SwipeGestureStateMachine.Action.None -> Unit

            is SwipeGestureStateMachine.Action.ArmPause -> {
                schedulePauseTimer(context, handler, settings)
            }

            SwipeGestureStateMachine.Action.CancelPause -> {
                cancelPendingPause(handler)
            }

            SwipeGestureStateMachine.Action.CancelActiveKeepBackSuppressed -> {
                cancelPendingPause(handler)
                extendBackSuppression(SystemClock.uptimeMillis() + 2000L)
                overlayClient?.hide(cancelled = true)
                logTrace { "Slide-back cancel triggered; back suppression kept latched until UP" }
            }

            is SwipeGestureStateMachine.Action.UpdateSelection -> {
                overlayClient?.updateSelection(action.selectedIndex, action.hitBoundary)
            }

            is SwipeGestureStateMachine.Action.ActivateSwitcher,
            is SwipeGestureStateMachine.Action.CommitSwitch,
            is SwipeGestureStateMachine.Action.EndGesture,
            -> Unit
        }
    }

    fun handleMultiTouchPointerDown(handler: Handler) {
        cancelPendingPause(handler)
        val action = stateMachine.onPointerDown()
        if (action is SwipeGestureStateMachine.Action.EndGesture && action.keepBackSuppressionWindow) {
            extendBackSuppression(SystemClock.uptimeMillis() + POST_UP_BACK_SUPPRESSION_GRACE_MS)
        }
        overlayClient?.hide(cancelled = true)
        activeCandidates = emptyList()
    }

    fun handlePointerUpOrCancel(
        context: Context,
        handler: Handler,
        cancelled: Boolean,
    ) {
        cancelPendingPause(handler)
        val candidatesSnapshot = activeCandidates
        activeCandidates = emptyList()

        when (val action = stateMachine.onUpOrCancel(cancelled)) {
            is SwipeGestureStateMachine.Action.CommitSwitch -> {
                extendBackSuppression(SystemClock.uptimeMillis() + POST_UP_BACK_SUPPRESSION_GRACE_MS)
                overlayClient?.hide(cancelled = false)
                val target = candidatesSnapshot.getOrNull(action.selectedIndex)
                if (target != null && target.taskId > 0) {
                    logTrace { "CommitSwitch index=${action.selectedIndex} pkg=${target.packageName} task=${target.taskId}" }
                    moveTaskToFrontAsync(context, target.taskId)
                }
            }

            is SwipeGestureStateMachine.Action.EndGesture -> {
                if (action.keepBackSuppressionWindow) {
                    extendBackSuppression(
                        SystemClock.uptimeMillis() + POST_UP_BACK_SUPPRESSION_GRACE_MS,
                    )
                } else {
                    extendBackSuppression(0L)
                }
                overlayClient?.hide(cancelled = true)
            }

            else -> Unit
        }
    }

    private fun schedulePauseTimer(
        context: Context,
        handler: Handler,
        settings: SwipeGestureSettings,
    ) {
        cancelPendingPause(handler)
        val seq = gestureSeq.get()
        val maxCount = if (settings.switchMode == SwitchMode.MULTI_APP_MENU) {
            settings.menuMaxCount
        } else {
            1
        }
        prefetchCandidatesAsync(context, seq, settings.blacklistedPackages, maxCount)

        val runnable = Runnable {
            pauseRunnable = null
            if (gestureSeq.get() != seq) return@Runnable

            val cached = prefetchedResult?.takeIf { it.seq == seq }?.items
            if (cached != null) {
                activateWithCandidates(settings, cached)
            } else {
                workerExecutor.execute {
                    val loaded = resolveRecentCandidates(context, settings.blacklistedPackages, maxCount)
                    handler.post {
                        if (gestureSeq.get() == seq) {
                            activateWithCandidates(settings, loaded)
                        }
                    }
                }
            }
        }
        pauseRunnable = runnable
        handler.postDelayed(runnable, settings.pauseDurationMs.toLong())
    }

    private fun prefetchCandidatesAsync(
        context: Context,
        seq: Int,
        blacklist: Set<String>,
        maxCount: Int,
    ) {
        workerExecutor.execute {
            if (gestureSeq.get() != seq) return@execute
            val loaded = resolveRecentCandidates(context, blacklist, maxCount)
            if (gestureSeq.get() == seq) {
                prefetchedResult = PrefetchedCandidates(seq = seq, items = loaded)
            }
        }
    }

    private fun activateWithCandidates(
        settings: SwipeGestureSettings,
        candidates: List<CandidateApp>,
    ) {
        when (val action = stateMachine.onPauseElapsed(candidates.size)) {
            is SwipeGestureStateMachine.Action.ActivateSwitcher -> {
                activeCandidates = candidates
                extendBackSuppression(SystemClock.uptimeMillis() + 5000L)
                triggerNativeBackCancel()
                overlayClient?.show(
                    OverlayShowPayload(
                        mode = settings.switchMode,
                        fromLeft = action.fromLeft,
                        anchorRawY = action.anchorY,
                        scale = settings.menuScale,
                        selectedIndex = 0,
                        candidates = candidates,
                        material = settings.overlayMaterial,
                        themeMode = settings.overlayThemeMode,
                    ),
                )
                logTrace { "Activated switcher fromLeft=${action.fromLeft} candidates=${candidates.size}" }
            }

            else -> {
                activeCandidates = emptyList()
            }
        }
    }

    private fun cancelPendingPause(handler: Handler) {
        pauseRunnable?.let { handler.removeCallbacks(it) }
        pauseRunnable = null
    }

    private fun extendBackSuppression(untilUptimeMs: Long) {
        backSuppressedUntilUptimeMs = untilUptimeMs
        overlayClient?.suppressBackUntil(untilUptimeMs)
    }

    private fun triggerNativeBackCancel() {
        runCatching {
            val monitor = miuiGestureMonitorInstance
            val pilfer = miuiPilferPointersMethod
            if (monitor != null && pilfer != null) {
                pilfer.invoke(monitor)
            }
        }.onFailure { err ->
            moduleRef?.log(Log.WARN, TAG, "pilferPointers failed", err)
        }

        val bnc = backNavControllerRef ?: return
        val handler = wmsHandler ?: return
        handler.post {
            cancelInFlightBackNavigationLocked(bnc)
        }
    }

    private fun cancelInFlightBackNavigationLocked(bnc: Any) {
        val lock = wmsGlobalLock
        val action = {
            runCatching {
                val navMonitorField = findField(bnc.javaClass, "mNavigationMonitor") ?: return@runCatching
                val navMonitor = navMonitorField.get(bnc) ?: return@runCatching
                val isForRemote = navMonitor.javaClass
                    .getDeclaredMethod("isMonitorForRemote")
                    .apply { isAccessible = true }
                    .invoke(navMonitor) as? Boolean ?: false
                val inProgress = findField(bnc.javaClass, "mBackAnimationInProgress")
                    ?.getBoolean(bnc) == true

                if (isForRemote || inProgress) {
                    navMonitor.javaClass
                        .getDeclaredMethod("cancelBackNavigating", String::class.java)
                        .apply { isAccessible = true }
                        .invoke(navMonitor, "hyperSwipeGesture")
                    logTrace { "Cancelled active BackNavigationController session cleanly" }
                }
            }
        }
        if (lock != null) {
            synchronized(lock) { action() }
        } else {
            action()
        }
    }

    private fun isSystemUiBlockingGesture(context: Context): Boolean {
        val keyguardLocked = runCatching {
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked == true
        }.getOrDefault(false)
        if (keyguardLocked) return true

        return if (systemControlBound) {
            systemUiBlockingFromOverlay
        } else {
            statusBarPanelRevealed
        }
    }

    private fun refreshDisplayMetrics(context: Context) {
        runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) {
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                if (size.x > 0 && size.y > 0) {
                    cachedScreenWidth = size.x
                    cachedScreenHeight = size.y
                }
            }
            val resDm = context.resources.displayMetrics
            if (resDm.density > 0f) {
                cachedDensity = resDm.density
            }
        }
    }

    private fun readImeVisibleHeightPx(): Int {
        val policy = wmsPolicy ?: return 0
        val field = imeVisibleHeightField ?: return 0
        return runCatching { field.getInt(policy).coerceAtLeast(0) }.getOrDefault(0)
    }

    private fun resolveSystemUiUid(): Int {
        val existing = systemUiUid
        if (existing > 0) return existing
        val ctx = systemContext ?: return -1
        return runCatching {
            ctx.packageManager.getPackageUid(SwipeGestureModuleEntry.PKG_SYSTEM_UI, 0)
        }.onSuccess { uid ->
            systemUiUid = uid
        }.getOrDefault(-1)
    }

    private fun resolveRecentCandidates(
        context: Context,
        blacklist: Set<String>,
        maxCount: Int,
    ): List<CandidateApp> {
        if (maxCount <= 0) return emptyList()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val result = ArrayList<CandidateApp>(maxCount)

        @Suppress("DEPRECATION")
        val tasks = runCatching { am.getRunningTasks(24) }.getOrNull().orEmpty()
        if (tasks.size < 2) return emptyList()

        val foregroundPkg = tasks.firstOrNull()?.baseActivity?.packageName

        for (i in 1 until tasks.size) {
            if (result.size >= maxCount) break
            val task = tasks[i]
            val base = task.baseActivity ?: continue
            val pkg = base.packageName
            if (pkg == foregroundPkg) continue
            if (pkg == SwipeGestureModuleEntry.PKG_LAUNCHER) continue
            if (pkg == SwipeGestureModuleEntry.PKG_SYSTEM_UI || pkg == "android") continue
            if (pkg in blacklist) continue
            if (isFloatingOrMiniWindow(task)) continue
            if (result.any { it.packageName == pkg }) continue

            result.add(CandidateApp(taskId = task.taskId, packageName = pkg, label = ""))
        }
        return result
    }

    private fun moveTaskToFrontAsync(context: Context, taskId: Int) {
        workerExecutor.execute {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.moveTaskToFront(taskId, 0)
            }.onFailure { err ->
                moduleRef?.log(Log.WARN, TAG, "moveTaskToFront($taskId) failed", err)
            }
        }
    }

    private fun isFloatingOrMiniWindow(task: ActivityManager.RunningTaskInfo): Boolean {
        val mode = readWindowingMode(task)
        if (mode == WINDOWING_MODE_FREEFORM || mode == WINDOWING_MODE_PC_FREEFORM) return true

        if (readBooleanField(task, "isMiniWindow") ||
            readBooleanField(task, "mIsMiniWindow") ||
            readBooleanField(task, "inFreeformMode")
        ) {
            return true
        }

        val className = task.baseActivity?.className?.lowercase(Locale.ROOT).orEmpty()
        return className.contains("miniwindow") || className.contains("freeform")
    }

    private fun readWindowingMode(task: ActivityManager.RunningTaskInfo): Int {
        runCatching {
            val configField = findField(task.javaClass, "configuration")
            val config = configField?.get(task) as? Configuration ?: return@runCatching
            val winConfig = config.javaClass.getMethod("getWindowConfiguration").invoke(config)
                ?: return@runCatching
            val mode = winConfig.javaClass.getMethod("getWindowingMode").invoke(winConfig) as? Int
            if (mode != null) return mode
        }
        runCatching {
            val modeField = findField(task.javaClass, "windowingMode")
            if (modeField != null) return modeField.getInt(task)
        }
        return 1
    }

    private fun readBooleanField(target: Any, name: String): Boolean = runCatching {
        findField(target.javaClass, name)?.getBoolean(target) ?: false
    }.getOrDefault(false)

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name).apply { isAccessible = true }
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    private inline fun logTrace(message: () -> String) {
        if (RuntimeSettingsHolder.current.diagnosticTraceEnabled) {
            moduleRef?.log(Log.DEBUG, TAG, message())
        }
    }

    private class BackCallbackReflection(
        val infoCallbackField: Field,
        val stubAsInterfaceMethod: Method,
        val descriptor: String,
        val txSetTriggerBack: Int,
        val txOnBackInvoked: Int,
        val txOnBackCancelled: Int,
        val txOnBackProgressed: Int,
        val txOnBackStarted: Int,
    ) {
        companion object {
            fun resolve(classLoader: ClassLoader): BackCallbackReflection? = runCatching {
                val infoClass = Class.forName("android.window.BackNavigationInfo", false, classLoader)
                val callbackField = infoClass.getDeclaredField("mOnBackInvokedCallback").apply {
                    isAccessible = true
                }
                val stubClass = Class.forName("android.window.IOnBackInvokedCallback\$Stub", false, classLoader)
                val asInterface = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
                val ifaceClass = Class.forName("android.window.IOnBackInvokedCallback", false, classLoader)
                val descriptor = ifaceClass.getDeclaredField("DESCRIPTOR").apply {
                    isAccessible = true
                }.get(null) as String

                fun txCode(fieldName: String): Int = stubClass.getDeclaredField(fieldName).apply {
                    isAccessible = true
                }.getInt(null)

                BackCallbackReflection(
                    infoCallbackField = callbackField,
                    stubAsInterfaceMethod = asInterface,
                    descriptor = descriptor,
                    txSetTriggerBack = txCode("TRANSACTION_setTriggerBack"),
                    txOnBackInvoked = txCode("TRANSACTION_onBackInvoked"),
                    txOnBackCancelled = txCode("TRANSACTION_onBackCancelled"),
                    txOnBackProgressed = txCode("TRANSACTION_onBackProgressed"),
                    txOnBackStarted = txCode("TRANSACTION_onBackStarted"),
                )
            }.getOrNull()
        }
    }

    private class BackCallbackSuppressionBinder(
        private val realBinder: IBinder,
        private val reflection: BackCallbackReflection,
        private val isSuppressed: () -> Boolean,
        private val logTrace: (() -> String) -> Unit,
    ) : Binder() {

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(reflection.descriptor)
                return true
            }

            if (isSuppressed()) {
                when (code) {
                    reflection.txOnBackInvoked -> {
                        data.enforceInterface(reflection.descriptor)
                        sendOneWayCancel()
                        logTrace { "Intercepted IOnBackInvokedCallback.onBackInvoked -> converted to onBackCancelled" }
                        return true
                    }

                    reflection.txSetTriggerBack -> {
                        data.enforceInterface(reflection.descriptor)
                        sendSetTriggerBackFalse(flags)
                        return true
                    }

                    reflection.txOnBackStarted,
                    reflection.txOnBackProgressed,
                    -> {
                        data.enforceInterface(reflection.descriptor)
                        return true
                    }
                }
            }

            val forwarded = Parcel.obtain()
            return try {
                forwarded.appendFrom(data, 0, data.dataSize())
                forwarded.setDataPosition(0)
                realBinder.transact(code, forwarded, reply, flags)
            } finally {
                forwarded.recycle()
            }
        }

        private fun sendOneWayCancel() {
            val cancelData = Parcel.obtain()
            try {
                cancelData.writeInterfaceToken(reflection.descriptor)
                realBinder.transact(reflection.txOnBackCancelled, cancelData, null, FLAG_ONEWAY)
            } catch (_: Throwable) {
                // Ignore dead target binder
            } finally {
                cancelData.recycle()
            }
        }

        private fun sendSetTriggerBackFalse(flags: Int) {
            val out = Parcel.obtain()
            try {
                out.writeInterfaceToken(reflection.descriptor)
                out.writeBoolean(false)
                realBinder.transact(reflection.txSetTriggerBack, out, null, flags or FLAG_ONEWAY)
            } catch (_: Throwable) {
                // Ignore dead target binder
            } finally {
                out.recycle()
            }
        }
    }
}
