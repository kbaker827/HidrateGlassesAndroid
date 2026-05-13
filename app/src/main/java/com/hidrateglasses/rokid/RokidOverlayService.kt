package com.hidrateglasses.rokid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.hidrateglasses.BuildConfig
import com.hidrateglasses.MainActivity
import com.hidrateglasses.data.models.HydrationData
import com.hidrateglasses.data.repository.HydrationRepository
import com.rokid.cxr.client.extend.CxrApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private const val TAG = "RokidOverlayService"

/**
 * Foreground service that maintains the CxrApi connection to Rokid glasses and
 * pushes live hydration data to the on-glass custom-view HUD.
 *
 * Bind to this service from other components to call [updateHydration] or
 * [sendReminder] directly, or let the service observe [HydrationRepository]
 * autonomously once started.
 */
@AndroidEntryPoint
class RokidOverlayService : Service() {

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"
        const val ACTION_UPDATE = "com.hidrateglasses.OVERLAY_UPDATE"
        const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val CHANNEL_SERVICE = "ROKID_SERVICE"
        const val CHANNEL_HYDRATION = "HYDRATION_REMINDERS"

        /** JSON layout sent to the glasses once on connection. */
        private val HUD_LAYOUT_JSON = """
            {
              "type": "column",
              "padding": 16,
              "children": [
                {"type": "text",        "id": "title",     "text": "Hydration",        "textSize": 18, "bold": true,  "color": "#FFFFFF"},
                {"type": "progressBar", "id": "ring",      "progress": 0, "max": 100,                 "color": "#4FC3F7"},
                {"type": "text",        "id": "intake",    "text": "0 / 0 oz",          "textSize": 28,               "color": "#FFFFFF"},
                {"type": "text",        "id": "lastDrink", "text": "Last drink: --",    "textSize": 14,               "color": "#B0BEC5"},
                {"type": "text",        "id": "temp",      "text": "Bottle temp: --°F","textSize": 14,           "color": "#B0BEC5"},
                {"type": "text",        "id": "battery",   "text": "Battery: --%",      "textSize": 12,               "color": "#78909C"}
              ]
            }
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // Binder — allows in-process clients to call service methods directly
    // -------------------------------------------------------------------------

    inner class LocalBinder : Binder() {
        fun getService(): RokidOverlayService = this@RokidOverlayService
    }

    private val binder = LocalBinder()

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    @Inject
    lateinit var repository: HydrationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Whether the glasses HUD custom view is currently open. */
    private var hudOpen = false

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())
        observeHydrationData()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        closeHud()
        scope.cancel()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Public API — callable via the binder or from RokidGlassesManager
    // -------------------------------------------------------------------------

    /**
     * Push a hydration data snapshot to the glasses HUD.
     * Opens the custom view on first call (if glasses are connected).
     */
    fun updateHydration(data: HydrationData) {
        if (!CxrApi.getInstance().isBluetoothConnected()) {
            Log.d(TAG, "updateHydration: glasses not connected, skipping")
            return
        }

        // Open HUD layout the first time we have a connected device
        if (!hudOpen) {
            openHud()
        }

        val lastDrinkStr = if (data.lastDrinkTimestamp > 0L) {
            val fmt = SimpleDateFormat("h:mm a", Locale.US)
            fmt.format(Date(data.lastDrinkTimestamp * 1000L))
        } else "--"

        val tempStr = if (data.temperatureF > 0f) {
            "%.1f".format(data.temperatureF)
        } else "--"

        val batteryStr = if (data.batteryPercent > 0) "${data.batteryPercent}%" else "--%"

        // Build the update array — each element targets a view by id
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
            append(fieldUpdate("battery",   "Battery: $batteryStr"))
            append("]")
        }

        try {
            CxrApi.getInstance().updateCustomView(updates)
            Log.d(TAG, "updateCustomView sent: $updates")
        } catch (e: Exception) {
            Log.e(TAG, "updateCustomView failed", e)
        }
    }

    /**
     * Show a toast reminder on the glasses display.
     *
     * @param message  Human-readable reminder text, e.g. "Time to drink water!"
     */
    fun sendReminder(message: String) {
        if (!CxrApi.getInstance().isBluetoothConnected()) {
            Log.d(TAG, "sendReminder: glasses not connected — falling back to notification")
            postPhoneNotification("Hydration Reminder", message)
            return
        }
        try {
            // type=1 is a standard informational toast in the Rokid global-message API
            CxrApi.getInstance().sendGlobalToastContent(1, message, true)
            Log.d(TAG, "sendGlobalToastContent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "sendGlobalToastContent failed", e)
            postPhoneNotification("Hydration Reminder", message)
        }
    }

    // -------------------------------------------------------------------------
    // HUD management
    // -------------------------------------------------------------------------

    private fun openHud() {
        try {
            CxrApi.getInstance().openCustomView(HUD_LAYOUT_JSON)
            hudOpen = true
            Log.d(TAG, "openCustomView succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "openCustomView failed", e)
        }
    }

    private fun closeHud() {
        if (!hudOpen) return
        try {
            CxrApi.getInstance().closeCustomView()
            hudOpen = false
            Log.d(TAG, "closeCustomView succeeded")
        } catch (e: Exception) {
            Log.e(TAG, "closeCustomView failed", e)
        }
    }

    // -------------------------------------------------------------------------
    // Repository observation
    // -------------------------------------------------------------------------

    private fun observeHydrationData() {
        scope.launch {
            repository.hydrationData.collectLatest { data ->
                updateHydration(data)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun postPhoneNotification(title: String, body: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_HYDRATION)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "Rokid HUD Service",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { description = "Keeps the Rokid glasses HUD alive" }
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_HYDRATION,
                    "Hydration Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Periodic reminders to drink water"
                    enableVibration(true)
                }
            )
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("HidrateGlasses Active")
            .setContentText("Hydration HUD running on Rokid glasses")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Build a single-field JSON update object. Defaults to "text" attribute. */
    private fun fieldUpdate(id: String, value: String, attr: String = "text"): String {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put(attr, value)
        return obj.toString()
    }
}
