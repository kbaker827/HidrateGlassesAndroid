package com.hidrateglasses.data.models

import com.google.gson.annotations.SerializedName

/**
 * A single drink event recorded by the HidrateSpark bottle.
 */
data class DrinkEvent(
    @SerializedName("id") val id: String,
    @SerializedName("user_id") val userId: String = "",
    @SerializedName("amount_oz") val amountOz: Float,
    /** Unix epoch seconds */
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("temperature_f") val temperatureF: Float = 0f,
    @SerializedName("source") val source: String = "bottle"
)

/**
 * Groups multiple [DrinkEvent]s under a human-readable date label.
 */
data class DrinkEventGroup(
    val dateLabel: String,
    val events: List<DrinkEvent>,
    val totalOz: Float = events.sumOf { it.amountOz.toDouble() }.toFloat()
)
