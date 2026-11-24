package com.healer.hyperos.swipegesture.ipc

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import com.healer.hyperos.swipegesture.policy.OverlayMaterial
import com.healer.hyperos.swipegesture.policy.OverlayThemeMode
import com.healer.hyperos.swipegesture.policy.SwitchMode

/**
 * Lightweight bidirectional oneway Binder protocol between the ultra-thin `system_server`
 * gesture controller and the `com.android.systemui` overlay host.
 *
 * Every transaction uses [IBinder.FLAG_ONEWAY] so neither process blocks on cross-process UI work.
 */
object OverlayChannelContract {
    const val DESCRIPTOR = "com.healer.hyperos.swipegesture.ipc.IOverlayHost"
    const val CONTROL_DESCRIPTOR = "com.healer.hyperos.swipegesture.ipc.ISystemServerControl"

    /** Explicit internal broadcast action used by SystemUI to hand its Binder token to system_server. */
    const val ACTION_REGISTER_OVERLAY_HOST =
        "com.healer.hyperos.swipegesture.internal.REGISTER_OVERLAY_HOST"
    const val EXTRA_OVERLAY_BINDER = "overlay_binder"

    const val TX_SHOW = IBinder.FIRST_CALL_TRANSACTION + 0
    const val TX_UPDATE_SELECTION = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TX_HIDE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TX_SUPPRESS_BACK_UNTIL = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TX_REGISTER_SYSTEM_CONTROL = IBinder.FIRST_CALL_TRANSACTION + 4

    const val TX_SET_SYSTEM_UI_BLOCKING = IBinder.FIRST_CALL_TRANSACTION + 0
}

data class CandidateApp(
    val taskId: Int,
    val packageName: String,
    val label: String,
)

data class OverlayShowPayload(
    val mode: SwitchMode,
    val fromLeft: Boolean,
    val anchorRawY: Float,
    val scale: Float,
    val selectedIndex: Int,
    val candidates: List<CandidateApp>,
    val material: OverlayMaterial = OverlayMaterial.SOFT_LIGHT_GLASS,
    val themeMode: OverlayThemeMode = OverlayThemeMode.FOLLOW_SYSTEM,
)

/** Client used inside `system_server` to push non-blocking oneway overlay updates to SystemUI. */
class OverlayBinderClient(
    private val remote: IBinder,
) {
    fun show(payload: OverlayShowPayload): Boolean = transactOneway(OverlayChannelContract.TX_SHOW) { data ->
        data.writeInt(payload.mode.wireValue)
        data.writeInt(if (payload.fromLeft) 1 else 0)
        data.writeFloat(payload.anchorRawY)
        data.writeFloat(payload.scale)
        data.writeInt(payload.selectedIndex)
        data.writeInt(payload.material.wireValue)
        data.writeInt(payload.themeMode.wireValue)
        data.writeInt(payload.candidates.size)
        for (candidate in payload.candidates) {
            data.writeInt(candidate.taskId)
            data.writeString(candidate.packageName)
            data.writeString(candidate.label)
        }
    }

    fun updateSelection(selectedIndex: Int, hitBoundary: Boolean): Boolean =
        transactOneway(OverlayChannelContract.TX_UPDATE_SELECTION) { data ->
            data.writeInt(selectedIndex)
            data.writeInt(if (hitBoundary) 1 else 0)
        }

    fun hide(cancelled: Boolean): Boolean = transactOneway(OverlayChannelContract.TX_HIDE) { data ->
        data.writeInt(if (cancelled) 1 else 0)
    }

    fun suppressBackUntil(untilUptimeMs: Long): Boolean =
        transactOneway(OverlayChannelContract.TX_SUPPRESS_BACK_UNTIL) { data ->
            data.writeLong(untilUptimeMs)
        }

    fun registerSystemControl(controlBinder: IBinder): Boolean =
        transactOneway(OverlayChannelContract.TX_REGISTER_SYSTEM_CONTROL) { data ->
            data.writeStrongBinder(controlBinder)
        }

    fun isAlive(): Boolean = runCatching { remote.isBinderAlive }.getOrDefault(false)

    private inline fun transactOneway(code: Int, writePayload: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(OverlayChannelContract.DESCRIPTOR)
            writePayload(data)
            remote.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
        }
    }
}

/** Binder stub hosted inside `com.android.systemui` to receive async overlay commands. */
class OverlayBinderHost(
    private val listener: Listener,
) : Binder() {

    interface Listener {
        fun onShow(payload: OverlayShowPayload)
        fun onUpdateSelection(selectedIndex: Int, hitBoundary: Boolean)
        fun onHide(cancelled: Boolean)
        fun onSuppressBackUntil(untilUptimeMs: Long)
        fun onSystemControlBound(controlBinder: IBinder)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(OverlayChannelContract.DESCRIPTOR)
            return true
        }
        if (getCallingUid() != Process.SYSTEM_UID) {
            return false
        }

        return when (code) {
            OverlayChannelContract.TX_SHOW -> {
                data.enforceInterface(OverlayChannelContract.DESCRIPTOR)
                val mode = SwitchMode.fromWireValue(data.readInt())
                val fromLeft = data.readInt() != 0
                val anchorRawY = data.readFloat()
                val scale = data.readFloat()
                val selectedIndex = data.readInt()
                val material = OverlayMaterial.fromWireValue(data.readInt())
                val themeMode = OverlayThemeMode.fromWireValue(data.readInt())
                val count = data.readInt().coerceIn(0, 20)
                val candidates = ArrayList<CandidateApp>(count)
                repeat(count) {
                    val taskId = data.readInt()
                    val pkg = data.readString().orEmpty()
                    val label = data.readString().orEmpty()
                    candidates.add(CandidateApp(taskId = taskId, packageName = pkg, label = label))
                }
                listener.onShow(
                    OverlayShowPayload(
                        mode = mode,
                        fromLeft = fromLeft,
                        anchorRawY = anchorRawY,
                        scale = scale,
                        selectedIndex = selectedIndex,
                        candidates = candidates,
                        material = material,
                        themeMode = themeMode,
                    ),
                )
                true
            }

            OverlayChannelContract.TX_UPDATE_SELECTION -> {
                data.enforceInterface(OverlayChannelContract.DESCRIPTOR)
                val selectedIndex = data.readInt()
                val hitBoundary = data.readInt() != 0
                listener.onUpdateSelection(selectedIndex, hitBoundary)
                true
            }

            OverlayChannelContract.TX_HIDE -> {
                data.enforceInterface(OverlayChannelContract.DESCRIPTOR)
                val cancelled = data.readInt() != 0
                listener.onHide(cancelled)
                true
            }

            OverlayChannelContract.TX_SUPPRESS_BACK_UNTIL -> {
                data.enforceInterface(OverlayChannelContract.DESCRIPTOR)
                val untilUptimeMs = data.readLong()
                listener.onSuppressBackUntil(untilUptimeMs)
                true
            }

            OverlayChannelContract.TX_REGISTER_SYSTEM_CONTROL -> {
                data.enforceInterface(OverlayChannelContract.DESCRIPTOR)
                val binder = data.readStrongBinder()
                if (binder != null) {
                    listener.onSystemControlBound(binder)
                }
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }
}

/** Client used inside `com.android.systemui` to push shade/control-center blocking state to `system_server`. */
class SystemServerControlClient(
    private val remote: IBinder,
) {
    fun setSystemUiBlocking(blocked: Boolean): Boolean {
        val data = Parcel.obtain()
        return try {
            data.writeInterfaceToken(OverlayChannelContract.CONTROL_DESCRIPTOR)
            data.writeInt(if (blocked) 1 else 0)
            remote.transact(OverlayChannelContract.TX_SET_SYSTEM_UI_BLOCKING, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: Throwable) {
            false
        } finally {
            data.recycle()
        }
    }
}

/** Binder stub hosted inside `system_server` receiving oneway SystemUI shade/keyguard state updates. */
class SystemServerControlHost(
    private val allowedUidProvider: () -> Int,
    private val onSystemUiBlockingChanged: (Boolean) -> Unit,
) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(OverlayChannelContract.CONTROL_DESCRIPTOR)
            return true
        }
        val expectedUid = allowedUidProvider()
        val callingUid = getCallingUid()
        if (expectedUid <= 0 || callingUid != expectedUid) {
            return false
        }

        return when (code) {
            OverlayChannelContract.TX_SET_SYSTEM_UI_BLOCKING -> {
                data.enforceInterface(OverlayChannelContract.CONTROL_DESCRIPTOR)
                val blocked = data.readInt() != 0
                onSystemUiBlockingChanged(blocked)
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }
}
