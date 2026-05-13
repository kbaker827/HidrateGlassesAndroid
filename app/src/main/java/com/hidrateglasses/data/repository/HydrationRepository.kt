package com.hidrateglasses.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hidrateglasses.BuildConfig
import com.hidrateglasses.ble.BleReadingEvent
import com.hidrateglasses.ble.HidrateBLEManager
import com.hidrateglasses.data.api.HidrateApiService
import com.hidrateglasses.data.api.TokenRequest
import com.hidrateglasses.data.models.DrinkEvent
import com.hidrateglasses.data.models.DrinkEventGroup
import com.hidrateglasses.data.models.HydrationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

// Top-level DataStore extension — one instance per application
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "hidrate_prefs")

object PreferencesKeys {
    val ACCESS_TOKEN = stringPreferencesKey("access_token")
    val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
    val DAILY_GOAL_OZ = floatPreferencesKey("daily_goal_oz")
    val LAST_SYNC_EPOCH = longPreferencesKey("last_sync_epoch")
    val USER_EMAIL = stringPreferencesKey("user_email")
    val UNITS = stringPreferencesKey("units") // "oz" or "ml"
}

@Singleton
class HydrationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: HidrateBLEManager,
    private val apiService: HidrateApiService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _hydrationData = MutableStateFlow(HydrationData())
    val hydrationData: StateFlow<HydrationData> = _hydrationData.asStateFlow()

    private val _drinkEvents = MutableStateFlow<List<DrinkEvent>>(emptyList())
    val drinkEvents: StateFlow<List<DrinkEvent>> = _drinkEvents.asStateFlow()

    val drinkEventGroups: Flow<List<DrinkEventGroup>>
        get() = kotlinx.coroutines.flow.flow {
            _drinkEvents.collect { events ->
                emit(groupEventsByDay(events))
            }
        }

    init {
        // Load cached goal
        scope.launch {
            val prefs = context.dataStore.data.firstOrNull()
            val savedGoal = prefs?.get(PreferencesKeys.DAILY_GOAL_OZ) ?: 64f
            _hydrationData.update { it.copy(goalOz = savedGoal) }
        }

        // Collect BLE readings
        bleManager.readings
            .onEach { event -> handleBleEvent(event) }
            .launchIn(scope)

        bleManager.isConnected
            .onEach { connected ->
                _hydrationData.update { it.copy(isConnected = connected) }
            }
            .launchIn(scope)
    }

    private fun handleBleEvent(event: BleReadingEvent) {
        when (event) {
            is BleReadingEvent.HydrationReading -> {
                _hydrationData.update { it.copy(todayOz = it.todayOz + event.deltaOz) }
            }
            is BleReadingEvent.TemperatureReading -> {
                _hydrationData.update { it.copy(temperatureF = event.temperatureF) }
            }
            is BleReadingEvent.BatteryReading -> {
                _hydrationData.update { it.copy(batteryPercent = event.percent) }
            }
            is BleReadingEvent.LastDrinkReading -> {
                _hydrationData.update { it.copy(lastDrinkTimestamp = event.epochSeconds) }
            }
        }
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        return try {
            val response = apiService.getToken(
                TokenRequest(
                    username = email,
                    password = password,
                    clientId = BuildConfig.HIDRATE_CLIENT_ID,
                    clientSecret = BuildConfig.HIDRATE_CLIENT_SECRET
                )
            )
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                context.dataStore.edit { prefs ->
                    prefs[PreferencesKeys.ACCESS_TOKEN] = body.accessToken
                    if (body.refreshToken.isNotBlank()) {
                        prefs[PreferencesKeys.REFRESH_TOKEN] = body.refreshToken
                    }
                    prefs[PreferencesKeys.USER_EMAIL] = email
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Login failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout() {
        context.dataStore.edit { prefs ->
            prefs.remove(PreferencesKeys.ACCESS_TOKEN)
            prefs.remove(PreferencesKeys.REFRESH_TOKEN)
        }
    }

    suspend fun isLoggedIn(): Boolean {
        val token = context.dataStore.data.firstOrNull()
            ?.get(PreferencesKeys.ACCESS_TOKEN)
        return !token.isNullOrBlank()
    }

    suspend fun getUserEmail(): String {
        return context.dataStore.data.firstOrNull()
            ?.get(PreferencesKeys.USER_EMAIL) ?: ""
    }

    suspend fun syncWithCloud(): Result<Unit> {
        _hydrationData.update { it.copy(isSyncing = true, errorMessage = null) }
        return try {
            // Fetch goal
            val goalResponse = apiService.getCurrentGoal()
            if (goalResponse.isSuccessful) {
                val goal = goalResponse.body()!!.dailyGoalOz
                context.dataStore.edit { it[PreferencesKeys.DAILY_GOAL_OZ] = goal }
                _hydrationData.update { it.copy(goalOz = goal) }
            }

            // Fetch today's events
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val eventsResponse = apiService.getHydrationEvents(
                startDate = today,
                endDate = today
            )
            if (eventsResponse.isSuccessful) {
                val events = eventsResponse.body()?.events ?: emptyList()
                _drinkEvents.value = events
                val totalOz = events.sumOf { it.amountOz.toDouble() }.toFloat()
                val lastDrink = events.maxByOrNull { it.timestamp }?.timestamp ?: 0L
                _hydrationData.update {
                    it.copy(
                        todayOz = totalOz,
                        lastDrinkTimestamp = lastDrink,
                        isSyncing = false
                    )
                }
            }

            // Also fetch recent history (last 7 days)
            val cal = Calendar.getInstance()
            val endDate = today
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val startDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            val historyResponse = apiService.getHydrationEvents(
                startDate = startDate,
                endDate = endDate,
                pageSize = 200
            )
            if (historyResponse.isSuccessful) {
                _drinkEvents.value = historyResponse.body()?.events ?: emptyList()
            }

            context.dataStore.edit {
                it[PreferencesKeys.LAST_SYNC_EPOCH] = System.currentTimeMillis()
            }
            _hydrationData.update { it.copy(isSyncing = false) }
            Result.success(Unit)
        } catch (e: Exception) {
            _hydrationData.update {
                it.copy(isSyncing = false, errorMessage = e.localizedMessage)
            }
            Result.failure(e)
        }
    }

    fun startBleScan() = bleManager.startScan()
    fun stopBleScan() = bleManager.stopScan()
    fun connectToDevice(address: String) = bleManager.connectToDevice(address)
    fun disconnectBle() = bleManager.disconnect()

    val scanResults = bleManager.scanResults
    val isScanning = bleManager.isScanning

    suspend fun setDailyGoal(oz: Float) {
        context.dataStore.edit { it[PreferencesKeys.DAILY_GOAL_OZ] = oz }
        _hydrationData.update { it.copy(goalOz = oz) }
    }

    suspend fun getUnits(): String {
        return context.dataStore.data.firstOrNull()
            ?.get(PreferencesKeys.UNITS) ?: "oz"
    }

    suspend fun setUnits(units: String) {
        context.dataStore.edit { it[PreferencesKeys.UNITS] = units }
    }

    private fun groupEventsByDay(events: List<DrinkEvent>): List<DrinkEventGroup> {
        val fmt = SimpleDateFormat("EEE, MMM d yyyy", Locale.US)
        return events
            .sortedByDescending { it.timestamp }
            .groupBy { event ->
                fmt.format(Date(event.timestamp * 1000L))
            }
            .map { (label, groupEvents) ->
                DrinkEventGroup(dateLabel = label, events = groupEvents)
            }
    }
}
