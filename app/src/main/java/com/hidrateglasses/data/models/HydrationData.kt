package com.hidrateglasses.data.models

/**
 * Snapshot of the current hydration state, combining BLE readings and cloud data.
 */
data class HydrationData(
    /** Total ounces consumed today */
    val todayOz: Float = 0f,
    /** Daily goal in ounces */
    val goalOz: Float = 64f,
    /** Bottle water temperature in Fahrenheit */
    val temperatureF: Float = 0f,
    /** Bottle battery percentage (0-100) */
    val batteryPercent: Int = 0,
    /** Unix epoch seconds of the last recorded drink */
    val lastDrinkTimestamp: Long = 0L,
    /** Whether the BLE device is currently connected */
    val isConnected: Boolean = false,
    /** Whether a cloud sync is in progress */
    val isSyncing: Boolean = false,
    /** Non-null when the last operation produced an error */
    val errorMessage: String? = null
) {
    /** Progress as a fraction [0.0, 1.0] */
    val progressFraction: Float
        get() = if (goalOz > 0f) (todayOz / goalOz).coerceIn(0f, 1f) else 0f

    /** Progress as an integer percentage */
    val progressPercent: Int
        get() = (progressFraction * 100).toInt()

    /** Remaining ounces to reach the daily goal */
    val remainingOz: Float
        get() = (goalOz - todayOz).coerceAtLeast(0f)
}
