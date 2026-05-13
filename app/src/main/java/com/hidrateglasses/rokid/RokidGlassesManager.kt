package com.hidrateglasses.rokid

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.hidrateglasses.BuildConfig
import com.hidrateglasses.data.models.HydrationData
import com.rokid.cxr.client.extend.BluetoothStatusCallback
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.CxrStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RokidGlassesManager"

/**
 * Singleton wrapper around the Rokid CXR-M SDK.
 *
 * Responsibilities:
 *  - BLE connection management via [CxrApi]
 *  - Custom-view HUD lifecycle (open / update / close)
 *  - Battery and screen-status callbacks forwarded to [glassesState]
 *  - Exposing [updateHydration] and [sendReminder] for the rest of the app
 *
 * Inject this class wherever the Rokid glasses need to be driven.
 */
@Singleton
class RokidGlassesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    data class GlassesState(
        val isConnected: Boolean = false,
        val batteryLevel: Int = -1,
        val screenOn: Boolean = false,
        val lastError: String? = null
    )

    private val _glassesState = MutableStateFlow(GlassesState())
    val glassesState: StateFlow<GlassesState> = _glassesState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether the custom-view HUD is currently open on the glasses display. */
    private var hudOpen = false

    // -------------------------------------------------------------------------
    // HUD layout — opened once after connection, updated on every data push
    // -------------------------------------------------------------------------

    private val HUD_LAYOUT_JSON = """
        {
          "type": "column",
          "padding": 16,
          "children": [
            {"type": "text",        "id": "title",     "text": "Hydration",         "textSize": 18, "bold": true,  "color": "#FFFFFF"},
            {"type": "progressBar", "id": "ring",      "progress": 0, "max": 100,                  "color": "#4FC3F7"},
            {"type": "text",        "id": "intake",    "text": "0 / 0 oz",          "textSize": 28,               "color": "#FFFFFF"},
            {"type": "text",        "id": "lastDrink", "text": "Last drink: --",    "textSize": 14,               "color": "#B0BEC5"},
            {"type": "text",        "id": "temp",      "text": "Bottle temp: --°F", "textSize": 14,               "color": "#B0BEC5"},
            {"type": "text",        "id": "battery",   "text": "Battery: --%",      "textSize": 12,               "color": "#78909C"}
          ]
        }
    """.trimIndent()

    // -------------------------------------------------------------------------
    // Connection
    // -------------------------------------------------------------------------

    /**
     * Initiate a BLE connection to the given [device].
     *
     * The SDK calls [callback] with a [CxrStatus] on every state change.
     * When [CxrStatus.BLUETOOTH_AVAILABLE] is received the HUD is opened
     * and the glasses listeners are registered.
     */
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect: ${device.address}")
        CxrApi.getInstance().initBluetooth(context, device, BluetoothStatusCallback { status ->
            Log.d(TAG, "CxrStatus: $status")
            when (status) {
                CxrStatus.BLUETOOTH_AVAILABLE -> {
                    _glassesState.value = _glassesState.value.copy(
                        isConnected = true,
                        lastError = null
                    )
                    registerListeners()
                    openHud()
                }
                else -> {
                    _glassesState.value = _glassesState.value.copy(
                        isConnected = false,
                        lastError = status.name
                    )
                    hudOpen = false
                }
            }
        })
    }

    /**
     * Reconnect to glasses by MAC address. Useful after app restart when the
     * [BluetoothDevice] object is no longer available but the address was persisted.
     *
     * @param socketUuid      UUID string used for the RFCOMM socket (pass empty string if unknown)
     * @param macAddress      Bluetooth MAC address of the glasses
     * @param snEncryptContent  SN-based encrypt content provided by the Rokid SDK pairing flow
     */
    fun reconnect(
        socketUuid: String,
        macAddress: String,
        snEncryptContent: String
    ) {
        Log.d(TAG, "reconnect: $macAddress")
        val clientSecret = BuildConfig.ROKID_CLIENT_SECRET
        CxrApi.getInstance().connectBluetooth(
            context,
            socketUuid,
            macAddress,
            BluetoothStatusCallback { status ->
                Log.d(TAG, "CxrStatus (reconnect): $status")
                when (status) {
                    CxrStatus.BLUETOOTH_AVAILABLE -> {
                        _glassesState.value = _glassesState.value.copy(
                            isConnected = true,
                            lastError = null
                        )
                        registerListeners()
                        openHud()
                    }
                    else -> {
                        _glassesState.value = _glassesState.value.copy(
                            isConnected = false,
                            lastError = status.name
                        )
                        hudOpen = false
                    }
                }
            },
            snEncryptContent,
            clientSecret
        )
    }

    /** @return true if the BLE link to the glasses is currently active. */
    fun isConnected(): Boolean = CxrApi.getInstance().isBluetoothConnected()

    // -------------------------------------------------------------------------
    // Data push
    // -------------------------------------------------------------------------

    /**
     * Push a [HydrationData] snapshot to the glasses HUD.
     * Opens the HUD if it is not yet visible.
     * No-ops silently when the glasses are disconnected.
     */
    fun updateHydration(data: HydrationData) {
        if (!CxrApi.getInstance().isBluetoothConnected()) {
            Log.d(TAG, "updateHydration: not connected, skipping")
            return
        }

        if (!hudOpen) {
            openHud()
            if (!hudOpen) return  // openHud failed
        }

        val lastDrinkStr = if (data.lastDrinkTimestamp > 0L) {
            SimpleDateFormat("h:mm a", Locale.US).format(Date(data.lastDrinkTimestamp * 1000L))
        } else "--"

        val tempStr = if (data.temperatureF > 0f) "%.1f".format(data.temperatureF) else "--"
        val bottleBattStr = if (data.batteryPercent > 0) "${data.batteryPercent}%" else "--%"

        val updates = buildString {
            append("[")
            append(fieldUpdate("ring",      data.progressPercent.toString(), "progress"))
            append(",")
            append(fieldUpdate("intake",    "${data.todayOz.toInt()} / ${data.goalOz.toInt()} oz"))
            append(",")
            append(fieldUpdate("lastDrink", "Last drink: $lastDrinkStr"))
            append(",")
            append(fieldUpdate("temp",      "Bottle temp: $tempStr°F"))
            append(",")
            append(fieldUpdate("battery",   "Battery: $bottleBattStr"))
            append("]")
        }

        try {
            CxrApi.getInstance().updateCustomView(updates)
            Log.d(TAG, "HUD updated: intake=${data.todayOz.toInt()}/${data.goalOz.toInt()} oz")
        } catch (e: Exception) {
            Log.e(TAG, "updateCustomView failed", e)
        }
    }

    /**
     * Send a drink-reminder toast to the glasses overlay.
     *
     * Falls back to a no-op when glasses are not connected (the caller, typically
     * [RokidOverlayService], is responsible for posting a phone notification in that case).
     */
    fun sendReminder(message: String) {
        if (!CxrApi.getInstance().isBluetoothConnected()) {
            Log.d(TAG, "sendReminder: not connected — no-op in manager (caller handles fallback)")
            return
        }
        try {
            // type = 1: standard informational toast
            CxrApi.getInstance().sendGlobalToastContent(1, message, true)
            Log.d(TAG, "sendGlobalToastContent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "sendGlobalToastContent failed", e)
        }
    }

    /**
     * Observe a [kotlinx.coroutines.flow.StateFlow] of [HydrationData] and automatically
     * push updates to the glasses HUD for the lifetime of this manager's coroutine scope.
     */
    fun observeAndPush(dataFlow: kotlinx.coroutines.flow.StateFlow<HydrationData>) {
        scope.launch {
            dataFlow.collectLatest { data ->
                updateHydration(data)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /**
     * Tear down the HUD and cancel all coroutines.
     * Call from the host Service or ViewModel's onDestroy.
     */
    fun destroy() {
        closeHud()
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // HUD lifecycle
    // -------------------------------------------------------------------------

    private fun openHud() {
        try {
            CxrApi.getInstance().openCustomView(HUD_LAYOUT_JSON)
            hudOpen = true
            Log.d(TAG, "openCustomView succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "openCustomView failed", e)
            hudOpen = false
        }
    }

    private fun closeHud() {
        if (!hudOpen) return
        try {
            CxrApi.getInstance().closeCustomView()
            Log.d(TAG, "closeCustomView succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "closeCustomView failed", e)
        } finally {
            hudOpen = false
        }
    }

    // -------------------------------------------------------------------------
    // SDK listeners
    // -------------------------------------------------------------------------

    private fun registerListeners() {
        CxrApi.getInstance().setBatteryLevelUpdateListener { level ->
            Log.d(TAG, "Glasses battery: $level%")
            _glassesState.value = _glassesState.value.copy(batteryLevel = level)
        }

        CxrApi.getInstance().setScreenStatusUpdateListener { isOn ->
            Log.d(TAG, "Glasses screen: ${if (isOn) "ON" else "OFF"}")
            _glassesState.value = _glassesState.value.copy(screenOn = isOn)
            // Re-open the HUD when the screen turns back on
            if (isOn && !hudOpen && CxrApi.getInstance().isBluetoothConnected()) {
                openHud()
            }
        }

        // AI / gesture button events — exposed for future use
        CxrApi.getInstance().setAiEventListener(object : com.rokid.cxr.client.extend.AiEventListener {
            override fun onAiKeyDown() {
                Log.d(TAG, "AI key down")
            }
            override fun onAiKeyUp() {
                Log.d(TAG, "AI key up")
            }
            override fun onAiExit() {
                Log.d(TAG, "AI exit")
            }
        })
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private fun fieldUpdate(id: String, value: String, attr: String = "text"): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put(attr, value)
        return obj.toString()
    }
}
