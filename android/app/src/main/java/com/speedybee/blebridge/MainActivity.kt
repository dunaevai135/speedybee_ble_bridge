package com.speedybee.blebridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), BridgeService.Callback {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var btnScan: Button
    private lateinit var btnStop: Button
    private lateinit var etUdpRemote: EditText
    private lateinit var etUdpLocal: EditText
    private lateinit var etPassword: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val logLines = StringBuilder()

    private var scanner: BluetoothLeScanner? = null
    private var activeScanCallback: ScanCallback? = null
    private var boundService: BridgeService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            boundService = (binder as BridgeService.LocalBinder).service
            boundService?.callback = this@MainActivity
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            boundService?.callback = null
            boundService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        scrollLog = findViewById(R.id.scrollLog)
        btnScan = findViewById(R.id.btnScan)
        btnStop = findViewById(R.id.btnStop)
        etUdpRemote = findViewById(R.id.etUdpRemote)
        etUdpLocal = findViewById(R.id.etUdpLocal)
        etPassword = findViewById(R.id.etPassword)

        btnScan.setOnClickListener { checkPermissionsAndScan() }
        btnStop.setOnClickListener {
            boundService?.stop()
            btnStop.isEnabled = false
            btnScan.isEnabled = true
        }

        bindToService()
    }

    override fun onDestroy() {
        boundService?.callback = null
        try { unbindService(serviceConnection) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun checkPermissionsAndScan() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            appendLog("Requesting permissions: ${missing.joinToString()}")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            startScan()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val denied = perms.zip(results.toList()).filter { it.second != PackageManager.PERMISSION_GRANTED }
        if (denied.isEmpty()) {
            startScan()
        } else {
            appendLog("Permissions denied: ${denied.map { it.first.substringAfterLast('.') }}")
        }
    }

    // ── BLE Scan ─────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter
        if (adapter == null) {
            appendLog("ERROR: No Bluetooth adapter found")
            return
        }
        if (!adapter.isEnabled) {
            appendLog("ERROR: Bluetooth is disabled — please enable it")
            return
        }

        // Check location services (still required for BLE scan on many devices)
        val locManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationOn = locManager.isLocationEnabled
        if (!locationOn) {
            appendLog("WARNING: Location services are OFF — BLE scan may return no results")
            appendLog("Please enable Location in system settings")
        }

        scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            appendLog("ERROR: BLE scanner not available (is Bluetooth turning on?)")
            return
        }

        btnScan.isEnabled = false
        tvStatus.text = "Status: Scanning (10s)..."
        appendLog("Starting BLE scan...")
        appendLog("  Bluetooth: ON, adapter=${adapter.name}")
        appendLog("  Location services: ${if (locationOn) "ON" else "OFF"}")

        val devices = mutableMapOf<String, ScanResult>()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val addr = result.device.address
                val isNew = addr !in devices
                devices[addr] = result
                if (isNew) {
                    val name = result.device.name ?: result.scanRecord?.deviceName ?: "(no name)"
                    handler.post {
                        appendLog("  Found: $name  $addr  RSSI=${result.rssi}")
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (r in results) {
                    devices[r.device.address] = r
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val reason = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "already started"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "app registration failed"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                    SCAN_FAILED_INTERNAL_ERROR -> "internal error"
                    else -> "unknown ($errorCode)"
                }
                handler.post {
                    appendLog("ERROR: BLE scan failed: $reason")
                    tvStatus.text = "Status: Scan failed"
                    btnScan.isEnabled = true
                }
            }
        }

        activeScanCallback = cb
        // Pass null for filters to see ALL devices
        scanner?.startScan(null, settings, cb)

        handler.postDelayed({
            try {
                scanner?.stopScan(cb)
            } catch (_: Exception) {}
            activeScanCallback = null
            appendLog("Scan complete: ${devices.size} device(s) found")
            showDeviceList(devices.values.toList())
        }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun showDeviceList(results: List<ScanResult>) {
        if (results.isEmpty()) {
            tvStatus.text = "Status: No devices found"
            btnScan.isEnabled = true
            appendLog("No BLE devices found. Check that:")
            appendLog("  1. SpeedyBee FC is powered on")
            appendLog("  2. Bluetooth is enabled")
            appendLog("  3. Location services are enabled")
            appendLog("  4. Permissions are granted")
            return
        }

        val sorted = results.sortedByDescending { it.rssi }
        val names = sorted.map { r ->
            val name = r.device.name ?: r.scanRecord?.deviceName ?: "(unknown)"
            "$name\n${r.device.address}  RSSI: ${r.rssi}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select device (${sorted.size})")
            .setItems(names) { _, which ->
                val device = sorted[which].device
                appendLog("Selected: ${device.name ?: device.address}")
                startBridge(device.address)
            }
            .setNegativeButton("Cancel") { _, _ ->
                btnScan.isEnabled = true
                tvStatus.text = "Status: Idle"
            }
            .setOnCancelListener {
                btnScan.isEnabled = true
                tvStatus.text = "Status: Idle"
            }
            .show()
    }

    // ── Start bridge service ─────────────────────────────────────────────────

    private fun startBridge(deviceAddr: String) {
        val remoteStr = etUdpRemote.text.toString().trim()
        val remoteHost: String
        val remotePort: Int
        if (":" in remoteStr) {
            val parts = remoteStr.split(":")
            remoteHost = parts[0]
            remotePort = parts[1].toIntOrNull() ?: 14550
        } else {
            remoteHost = remoteStr
            remotePort = 14550
        }
        val localPort = etUdpLocal.text.toString().trim().toIntOrNull() ?: 14551

        val password = etPassword.text.toString().trim().ifEmpty { null }

        val intent = Intent(this, BridgeService::class.java).apply {
            putExtra(BridgeService.EXTRA_DEVICE_ADDR, deviceAddr)
            putExtra(BridgeService.EXTRA_UDP_REMOTE_HOST, remoteHost)
            putExtra(BridgeService.EXTRA_UDP_REMOTE_PORT, remotePort)
            putExtra(BridgeService.EXTRA_UDP_LOCAL_PORT, localPort)
            putExtra(BridgeService.EXTRA_PASSWORD, password)
        }

        ContextCompat.startForegroundService(this, intent)
        bindToService()

        btnScan.isEnabled = false
        btnStop.isEnabled = true
        tvStatus.text = "Status: Connecting..."
    }

    private fun bindToService() {
        val intent = Intent(this, BridgeService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    // ── Service callbacks ────────────────────────────────────────────────────

    override fun onLog(msg: String) {
        handler.post { appendLog(msg) }
    }

    override fun onStatusChanged(status: String) {
        handler.post {
            tvStatus.text = "Status: $status"
            if (status == "Stopped") {
                btnScan.isEnabled = true
                btnStop.isEnabled = false
            }
        }
    }

    private fun appendLog(msg: String) {
        logLines.append(msg).append('\n')
        val lines = logLines.toString().lines()
        if (lines.size > 200) {
            logLines.clear()
            logLines.append(lines.takeLast(200).joinToString("\n"))
        }
        tvLog.text = logLines.toString()
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
