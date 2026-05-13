package com.hidrateglasses.rokid

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.hidrateglasses.BuildConfig
import com.hidrateglasses.data.models.HydrationData
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.utils.ValueUtil
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

@Singleton
class RokidGlassesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class GlassesState(
        val isConnected: Boolean = false,
        val batteryLevel: Int = -1,
        val screenOn: Boolean = false,
        val lastError: String? = null
    )

    private val _glassesState = MutableStateFlow(GlassesState())
    val glassesState: StateFlow<GlassesState> = _glassesState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hudOpen = false

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

    private val bluetoothCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(
            socketUuid: String?,
            macAddress: String?,
            rokidAccount: String?,
            deviceType: Int
        ) {
            Log.d(TAG, "onConnectionInfo: $macAddress")
        }

        override fun onConnected() {
            Log.d(TAG, "onConnected")
            _glassesState.value = _glassesState.value.copy(isConnected = true, lastError = null)
            registerListeners()
            openHud()
        }

        override fun onDisconnected() {
            Log.d(TAG, "onDisconnected")
            _glassesState.value = _glassesState.value.copy(isConnected = false)
            hudOpen = false
        }

        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            Log.e(TAG, "onFailed: $errorCode")
            _glassesState.value = _glassesState.value.copy(
                isConnected = false,
                lastError = errorCode?.name
            )
            hudOpen = false
        }
    }

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "connect: ${device.address}")
        CxrApi.getInstance().initBluetooth(context, device, bluetoothCallback)
    }

    fun reconnect(socketUuid: String, macAddress: String, snEncryptContent: ByteArray) {
        Log.d(TAG, "reconnect: $macAddress")
        val clientSecret = BuildConfig.ROKID_CLIENT_SECRET
        CxrApi.getInstance().connectBluetooth(
            context,
            socketUuid,
            macAddress,
            bluetoothCallback,
            snEncryptContent,
            clientSecret
        )
    }

    fun isConnected(): Boolean = CxrApi.getInstance().isBluetoothConnected()

    fun updateHydration(data: HydrationData) {
        if (!CxrApi.getInstance().isBluetoothConnected()) return
        if (!hudOpen) {
            openHud()
            if (!hudOpen) return
        }

        val lastDrinkStr = if (data.lastDrinkTimestamp > 0L) {
            SimpleDateFormat("h:mm a", Locale.US).format(Date(data.lastDrinkTimestamp * 1000L))
        } else "--"

        val tempStr = if (data.temperatureF > 0f) "%.1f".format(data.temperatureF) else "--"
        val bottleBattStr = if (data.batteryPercent > 0) "${data.batteryPercent}%" else "--%"

        val updates = buildString {
            append("[")
            append(fieldUpdate("ring", data.progressPercent.toString(), "progress"))
            append(",")
            append(fieldUpdate("intake", "${data.todayOz.toInt()} / ${data.goalOz.toInt()} oz"))
            append(",")
            append(fieldUpdate("lastDrink", "Last drink: $lastDrinkStr"))
            append(",")
            append(fieldUpdate("temp", "Bottle temp: $tempStr°F"))
            append(",")
            append(fieldUpdate("battery", "Battery: $bottleBattStr"))
            append("]")
        }

        try {
            CxrApi.getInstance().updateCustomView(updates)
        } catch (e: Exception) {
            Log.e(TAG, "updateCustomView failed", e)
        }
    }

    fun sendReminder(message: String) {
        if (!CxrApi.getInstance().isBluetoothConnected()) return
        try {
            CxrApi.getInstance().sendGlobalToastContent(1, message, true)
        } catch (e: Exception) {
            Log.e(TAG, "sendGlobalToastContent failed", e)
        }
    }

    fun observeAndPush(dataFlow: StateFlow<HydrationData>) {
        scope.launch {
            dataFlow.collectLatest { data -> updateHydration(data) }
        }
    }

    fun destroy() {
        closeHud()
        scope.cancel()
    }

    private fun openHud() {
        try {
            CxrApi.getInstance().openCustomView(HUD_LAYOUT_JSON)
            hudOpen = true
        } catch (e: Exception) {
            Log.e(TAG, "openCustomView failed", e)
            hudOpen = false
        }
    }

    private fun closeHud() {
        if (!hudOpen) return
        try {
            CxrApi.getInstance().closeCustomView()
        } catch (e: Exception) {
            Log.e(TAG, "closeCustomView failed", e)
        } finally {
            hudOpen = false
        }
    }

    private fun registerListeners() {
        CxrApi.getInstance().setBatteryLevelUpdateListener { level, _ ->
            Log.d(TAG, "Glasses battery: $level%")
            _glassesState.value = _glassesState.value.copy(batteryLevel = level)
        }

        CxrApi.getInstance().setScreenStatusUpdateListener { isOn ->
            Log.d(TAG, "Glasses screen: ${if (isOn) "ON" else "OFF"}")
            _glassesState.value = _glassesState.value.copy(screenOn = isOn)
            if (isOn && !hudOpen && CxrApi.getInstance().isBluetoothConnected()) {
                openHud()
            }
        }

        CxrApi.getInstance().setAiEventListener(object : AiEventListener {
            override fun onAiKeyDown() { Log.d(TAG, "AI key down") }
            override fun onAiKeyUp() { Log.d(TAG, "AI key up") }
            override fun onAiExit() { Log.d(TAG, "AI exit") }
        })
    }

    private fun fieldUpdate(id: String, value: String, attr: String = "text"): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put(attr, value)
        return obj.toString()
    }
}
