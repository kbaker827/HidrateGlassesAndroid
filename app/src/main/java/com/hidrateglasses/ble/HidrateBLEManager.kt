package com.hidrateglasses.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// ---------------------------------------------------------------------------
// BLE UUIDs for HidrateSpark
// ---------------------------------------------------------------------------

object HidrateUUID {
    val SERVICE = UUID.fromString("1BC5FFA0-0200-62AB-E411-F254E005DBD3")
    val HYDRATION = UUID.fromString("1BC5FFA1-0200-62AB-E411-F254E005DBD3")
    val TEMPERATURE = UUID.fromString("1BC5FFA2-0200-62AB-E411-F254E005DBD3")
    val BATTERY = UUID.fromString("1BC5FFA3-0200-62AB-E411-F254E005DBD3")
    val LAST_DRINK = UUID.fromString("1BC5FFA4-0200-62AB-E411-F254E005DBD3")
    val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

// ---------------------------------------------------------------------------
// Sealed events emitted by the BLE manager
// ---------------------------------------------------------------------------

sealed class BleReadingEvent {
    /** Delta ounces since last notification */
    data class HydrationReading(val deltaOz: Float) : BleReadingEvent()
    data class TemperatureReading(val temperatureF: Float) : BleReadingEvent()
    data class BatteryReading(val percent: Int) : BleReadingEvent()
    data class LastDrinkReading(val epochSeconds: Long) : BleReadingEvent()
}

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int
)

// ---------------------------------------------------------------------------
// Manager
// ---------------------------------------------------------------------------

private const val TAG = "HidrateBLE"

@Singleton
class HidrateBLEManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private var leScanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    private val _readings = MutableSharedFlow<BleReadingEvent>(extraBufferCapacity = 64)
    val readings: SharedFlow<BleReadingEvent> = _readings.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scanResults: StateFlow<List<ScannedDevice>> = _scanResults.asStateFlow()

    // ---------------------------------------------------------------------------
    // Permission helpers
    // ---------------------------------------------------------------------------

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context, Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ---------------------------------------------------------------------------
    // Scan
    // ---------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "Missing BLE permissions — cannot scan")
            return
        }
        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Bluetooth not available")
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth is disabled")
            return
        }

        leScanner = adapter.bluetoothLeScanner
        _scanResults.value = emptyList()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(HidrateUUID.SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _isScanning.value = true
        leScanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        leScanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d(TAG, "BLE scan stopped")
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "HidrateSpark"
            val address = device.address
            val rssi = result.rssi
            val existing = _scanResults.value.toMutableList()
            if (existing.none { it.address == address }) {
                existing.add(ScannedDevice(name, address, rssi))
                _scanResults.value = existing
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    // ---------------------------------------------------------------------------
    // GATT connection
    // ---------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        if (!hasBluetoothPermission()) return
        stopScan()
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(address) ?: return
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        Log.d(TAG, "Connecting to $address")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _isConnected.value = false
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected — discovering services")
                    _isConnected.value = true
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected")
                    _isConnected.value = false
                    gatt.close()
                    this@HidrateBLEManager.gatt = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = gatt.getService(HidrateUUID.SERVICE) ?: run {
                Log.e(TAG, "HidrateSpark service not found")
                return
            }
            Log.d(TAG, "HidrateSpark service discovered — enabling notifications")

            // Enable notifications on all characteristics
            listOf(
                HidrateUUID.HYDRATION,
                HidrateUUID.TEMPERATURE,
                HidrateUUID.BATTERY,
                HidrateUUID.LAST_DRINK
            ).forEach { uuid ->
                service.getCharacteristic(uuid)?.let { char ->
                    enableNotification(gatt, char)
                    // Also do an initial read
                    gatt.readCharacteristic(char)
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun enableNotification(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
            gatt.setCharacteristicNotification(char, true)
            char.getDescriptor(HidrateUUID.CLIENT_CHARACTERISTIC_CONFIG)?.let { descriptor ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(
                        descriptor,
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                parseCharacteristic(characteristic.uuid, characteristic.value)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                parseCharacteristic(characteristic.uuid, value)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            parseCharacteristic(characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            parseCharacteristic(characteristic.uuid, value)
        }
    }

    // ---------------------------------------------------------------------------
    // Data parsing
    // ---------------------------------------------------------------------------

    private fun parseCharacteristic(uuid: UUID, value: ByteArray?) {
        if (value == null || value.isEmpty()) return
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        scope.launch {
            when (uuid) {
                HidrateUUID.HYDRATION -> {
                    // 4-byte float: delta ounces
                    if (value.size >= 4) {
                        val deltaOz = buf.float
                        _readings.emit(BleReadingEvent.HydrationReading(deltaOz))
                    }
                }
                HidrateUUID.TEMPERATURE -> {
                    // 4-byte float: temperature in Fahrenheit
                    if (value.size >= 4) {
                        val tempF = buf.float
                        _readings.emit(BleReadingEvent.TemperatureReading(tempF))
                    }
                }
                HidrateUUID.BATTERY -> {
                    // 1-byte unsigned: battery percentage
                    val percent = value[0].toInt() and 0xFF
                    _readings.emit(BleReadingEvent.BatteryReading(percent))
                }
                HidrateUUID.LAST_DRINK -> {
                    // 4-byte unsigned int: Unix epoch seconds
                    if (value.size >= 4) {
                        val epochSec = buf.int.toLong() and 0xFFFFFFFFL
                        _readings.emit(BleReadingEvent.LastDrinkReading(epochSec))
                    }
                }
            }
        }
    }
}
