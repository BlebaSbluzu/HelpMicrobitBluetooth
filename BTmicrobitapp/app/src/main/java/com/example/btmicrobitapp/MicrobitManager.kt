package com.example.microbitconnect

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import java.nio.charset.Charset
import java.util.UUID

class MicrobitManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val scanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null

    private var isReady: Boolean = false

    private val UART_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val UART_RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val UART_TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var uartWriteChar: BluetoothGattCharacteristic? = null

    private var onUartLine: ((String) -> Unit)? = null
    fun setOnUartLine(listener: (String) -> Unit) {
        onUartLine = listener
    }

    private val rxBuffer = StringBuilder()

    @SuppressLint("MissingPermission")
    fun scanAndConnect(onStatus: (String) -> Unit) {
        onStatus("Scanning...")

        stopScan()

        if (bluetoothAdapter == null || bluetoothAdapter?.isEnabled != true) {
            onStatus("Bluetooth is OFF")
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = dev.name ?: return

                if (!name.startsWith("BBC micro:bit")) return

                onStatus("Found $name — connecting...")
                stopScan()
                connectDevice(dev, onStatus)
            }
        }

        scanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        isReady = false
        uartWriteChar = null
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    @SuppressLint("MissingPermission")
    fun sendText(text: String) {
        if (!isReady) {
            Log.d("MB", "sendText ignored: not ready")
            return
        }

        val gatt = bluetoothGatt ?: run {
            Log.d("MB", "sendText ignored: gatt is null")
            return
        }

        val ch = uartWriteChar ?: run {
            Log.d("MB", "sendText ignored: uartWriteChar is null")
            return
        }

        val props = ch.properties
        val canWrite = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val canWriteNoRsp = (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        val writeType = if (canWriteNoRsp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val result = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(ch, bytes, writeType)
        } else {
            ch.writeType = writeType
            ch.value = bytes
            if (gatt.writeCharacteristic(ch)) 0 else -1
        }

        Log.d("MB", "WRITE uuid=${ch.uuid} props=$props write=$canWrite noRsp=$canWriteNoRsp result=$result text='$text'")
    }


    @SuppressLint("MissingPermission")
    private fun connectDevice(device: BluetoothDevice, onStatus: (String) -> Unit) {
        bluetoothGatt?.close()
        bluetoothGatt = null
        isReady = false
        uartWriteChar = null
        rxBuffer.clear()

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onStatus("Connection failed (status=$status)")
                    Log.d("MB", "onConnectionStateChange fail status=$status newState=$newState")
                    gatt.close()
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    onStatus("Connected — discovering services...")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isReady = false
                    uartWriteChar = null
                    onStatus("Disconnected")
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onStatus("Service discovery failed (status=$status)")
                    return
                }

                val uartService = gatt.getService(UART_SERVICE_UUID)
                if (uartService == null) {
                    onStatus("Connected, but UART service not found")
                    Log.d("MB", "UART service NOT found: $UART_SERVICE_UUID")
                    return
                }

                val candidates = uartService.characteristics
                uartWriteChar = candidates.firstOrNull { ch ->
                    val p = ch.properties
                    (p and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                }

                for (ch in candidates) {
                    val props = ch.properties
                    val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

                    Log.d("MB", "UART char ${ch.uuid} props=$props notify=$canNotify indicate=$canIndicate perms=${ch.permissions}")

                    if (canNotify || canIndicate) {
                        enableNotify(gatt, ch, canIndicate)
                    }
                }

                isReady = true
                onStatus("Ready YAY (UART)")
                Log.d("MB", "Ready: writeChar=${uartWriteChar?.uuid}")
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val bytes = characteristic.value ?: return
                val text = bytes.toString(Charset.forName("UTF-8"))
                Log.d("MB", "RX<- ${characteristic.uuid} bytes=${bytes.size} text='$text'")

                // Turn stream into lines
                rxBuffer.append(text)
                while (true) {
                    val idx = rxBuffer.indexOf("\n")
                    if (idx < 0) break
                    val line = rxBuffer.substring(0, idx).trim()
                    rxBuffer.delete(0, idx + 1)
                    if (line.isNotEmpty()) onUartLine?.invoke(line)
                }
            }
        }

        bluetoothGatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, indicate: Boolean) {
        gatt.setCharacteristicNotification(ch, true)

        val cccd = ch.getDescriptor(CCCD_UUID) ?: run {
            Log.d("MB", "No CCCD for ${ch.uuid}")
            return
        }

        val enableValue = if (indicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        val ok = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(cccd, enableValue) == BluetoothStatusCodes.SUCCESS
        } else {
            cccd.value = enableValue
            gatt.writeDescriptor(cccd)
        }

        Log.d("MB", "enableNotify uuid=${ch.uuid} indicate=$indicate writeDescOk=$ok")
    }
}
