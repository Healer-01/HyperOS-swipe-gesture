package com.healer.hyperos.swipegesture.ipc

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.healer.hyperos.swipegesture.policy.SwitchMode

/**
 * Lightweight oneway Binder protocol between ultra-thin `system_server` gesture controller
 * and `com.android.systemui` overlay renderer.
 *
 * Every transaction uses [IBinder.FLAG_ONEWAY] so `system_server` never blocks waiting on UI work.
 */
object OverlayChannelContract {
    const val DESCRIPTOR = "com.healer.hyperos.swipegesture.ipc.IOverlayHost"

    /** Explicit internal broadcast action used by SystemUI to hand its Binder token to system_server. */
    const val ACTION_REGISTER_OVERLAY_HOST =
        "com.healer.hyperos.swipegesture.internal.REGISTER_OVERLAY_HOST"
    const val EXTRA_OVERLAY_BINDER = "overlay_binder"

    const val TX_SHOW = IBinder.FIRST_CALL_TRANSACTION + 0
    const val TX_UPDATE_SELECTION = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TX_HIDE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TX_SUPPRESS_BACK_UNTIL = IBinder.FIRST_CALL_TRANSACTION + 3
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
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        return when (code) {
            INTERFACE_TRANSACTION -> {
                reply?.writeString(OverlayChannelContract.DESCRIPTOR)
                true
            }

            OverlayChannelContract.TX_SHOW -> {
                data.enforceInterface(OverlayChannelContract.DESCRIPTOR)
                val mode = SwitchMode.fromWireValue(data.readInt())
                val fromLeft = data.readInt() != 0
                val anchorRawY = data.readFloat()
                val scale = data.readFloat()
                val selectedIndex = data.readInt()
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

            else -> super.onTransact(code, data, reply, flags)
        }
    }
}
