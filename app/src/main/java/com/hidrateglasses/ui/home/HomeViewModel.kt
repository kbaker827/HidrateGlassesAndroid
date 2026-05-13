package com.hidrateglasses.ui.home

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hidrateglasses.ble.ScannedDevice
import com.hidrateglasses.data.models.DrinkEvent
import com.hidrateglasses.data.models.HydrationData
import com.hidrateglasses.data.repository.HydrationRepository
import com.hidrateglasses.rokid.RokidOverlayService
import com.hidrateglasses.rokid.RokidPresentationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val hydrationData: HydrationData = HydrationData(),
    val scanResults: List<ScannedDevice> = emptyList(),
    val isScanning: Boolean = false,
    val showScanDialog: Boolean = false,
    val overlayEnabled: Boolean = false,
    val rokidPresentationEnabled: Boolean = false,
    val snackbarMessage: String? = null,
    val isLoggedIn: Boolean = false,
    val userEmail: String = ""
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: HydrationRepository,
    private val presentationManager: RokidPresentationManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val recentEvents: StateFlow<List<DrinkEvent>> = repository.drinkEvents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Observe hydration data from repository
        viewModelScope.launch {
            repository.hydrationData.collect { data ->
                _uiState.update { it.copy(hydrationData = data) }
            }
        }
        // Observe BLE scan results
        viewModelScope.launch {
            repository.scanResults.collect { results ->
                _uiState.update { it.copy(scanResults = results) }
            }
        }
        // Observe scanning state
        viewModelScope.launch {
            repository.isScanning.collect { scanning ->
                _uiState.update { it.copy(isScanning = scanning) }
            }
        }
        // Check login state
        viewModelScope.launch {
            val loggedIn = repository.isLoggedIn()
            val email = if (loggedIn) repository.getUserEmail() else ""
            _uiState.update { it.copy(isLoggedIn = loggedIn, userEmail = email) }
            if (loggedIn) {
                syncData()
            }
        }
        // Start Rokid presentation manager listening for secondary displays
        presentationManager.start(repository.hydrationData)
    }

    fun startScan() {
        repository.startBleScan()
        _uiState.update { it.copy(showScanDialog = true) }
    }

    fun stopScan() {
        repository.stopBleScan()
        _uiState.update { it.copy(showScanDialog = false) }
    }

    fun connectToDevice(address: String) {
        repository.connectToDevice(address)
        _uiState.update { it.copy(showScanDialog = false) }
        showSnackbar("Connecting to device…")
    }

    fun syncData() {
        viewModelScope.launch {
            val result = repository.syncWithCloud()
            if (result.isSuccess) {
                showSnackbar("Sync complete")
            } else {
                showSnackbar("Sync failed: ${result.exceptionOrNull()?.localizedMessage}")
            }
        }
    }

    fun toggleOverlay(enable: Boolean) {
        if (enable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                // Open the settings screen; the caller observes the result
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                showSnackbar("Grant 'Display over other apps', then toggle again")
                return
            }
            val serviceIntent = Intent(context, RokidOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            context.stopService(Intent(context, RokidOverlayService::class.java))
        }
        _uiState.update { it.copy(overlayEnabled = enable) }
    }

    fun toggleRokidPresentation(enable: Boolean) {
        if (enable) {
            presentationManager.start(repository.hydrationData)
        } else {
            presentationManager.stop()
        }
        _uiState.update { it.copy(rokidPresentationEnabled = enable) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun dismissScanDialog() {
        stopScan()
    }

    private fun showSnackbar(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    override fun onCleared() {
        super.onCleared()
        presentationManager.stop()
    }
}
