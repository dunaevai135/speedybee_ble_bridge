package com.speedybee.blebridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BridgeService : Service() {

    companion object {
        const val CHANNEL_ID = "speedybee_bridge"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.speedybee.blebridge.STOP"

        const val EXTRA_DEVICE_ADDR = "device_addr"
        const val EXTRA_UDP_REMOTE_HOST = "udp_remote_host"
        const val EXTRA_UDP_REMOTE_PORT = "udp_remote_port"
        const val EXTRA_UDP_LOCAL_PORT = "udp_local_port"
        const val EXTRA_PASSWORD = "password"
    }

    interface Callback {
        fun onLog(msg: String)
        fun onStatusChanged(status: String)
    }

    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }

    private val binder = LocalBinder()
    var callback: Callback? = null

    private var gatt: BluetoothGatt? = null
    private var serialTx: BluetoothGattCharacteristic? = null
    private var udpSocket: DatagramSocket? = null
    private var udpRemoteAddr: InetAddress? = null
    private var udpRemotePort: Int = 14550
    private var mtu: Int = 23
    private var password: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false

    // Synchronization for sequential GATT operations
    private var descriptorLatch: CountDownLatch? = null
    private var writeLatch: CountDownLatch? = null

    // Handshake notification buffer
    private val handshakeRespBuf = mutableListOf<Byte>()
    private var handshakeRespLatch: CountDownLatch? = null

    // Handshake state: true while handshake is in progress (ABF4 data goes to handshakeBuf)
    @Volatile private var handshakePhase = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Connecting..."))
        acquireWakeLock()

        val addr = intent?.getStringExtra(EXTRA_DEVICE_ADDR) ?: run {
            log("No device address provided")
            stopSelf()
            return START_NOT_STICKY
        }
        udpRemoteAddr = InetAddress.getByName(
            intent.getStringExtra(EXTRA_UDP_REMOTE_HOST) ?: "127.0.0.1"
        )
        udpRemotePort = intent.getIntExtra(EXTRA_UDP_REMOTE_PORT, 14550)
        val localPort = intent.getIntExtra(EXTRA_UDP_LOCAL_PORT, 14551)
        password = intent.getStringExtra(EXTRA_PASSWORD)

        running = true
        startUdpListener(localPort)
        connectBle(addr)

        return START_STICKY
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        running = false
        log("Stopping bridge...")
        callback?.onStatusChanged("Stopping...")

        try { udpSocket?.close() } catch (_: Exception) {}
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        releaseWakeLock()

        callback?.onStatusChanged("Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun log(msg: String) {
        android.util.Log.d("BridgeService", msg)
        callback?.onLog(msg)
    }

    private fun updateStatus(status: String) {
        updateNotification(status)
        callback?.onStatusChanged(status)
    }

    // ── BLE ──────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun connectBle(address: String) {
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val device: BluetoothDevice = btManager.adapter.getRemoteDevice(address)
        log("Connecting to $address...")
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("BLE connected, requesting MTU...")
                g.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("BLE disconnected (status=$status)")
                if (running) {
                    updateStatus("Disconnected, reconnecting...")
                    thread {
                        Thread.sleep(2000)
                        if (running) {
                            val addr = g.device.address
                            @SuppressLint("MissingPermission")
                            g.close()
                            connectBle(addr)
                        }
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtu = newMtu
            log("MTU=$newMtu")
            g.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Service discovery failed: $status")
                return
            }

            val svc = g.getService(SpeedyBeeGatt.SVC_SPEEDYBEE)
            if (svc == null) {
                log("SpeedyBee service 0xABF0 not found!")
                g.services.forEach { log("  Service: ${it.uuid}") }
                return
            }

            log("Found SpeedyBee service")
            svc.characteristics.forEach {
                log("  Char ${it.uuid} props=${it.properties}")
            }

            serialTx = svc.getCharacteristic(SpeedyBeeGatt.CHR_SERIAL_TX)
            val serialRx = svc.getCharacteristic(SpeedyBeeGatt.CHR_SERIAL_RX)

            if (serialTx == null || serialRx == null) {
                log("TX (ABF1) or RX (ABF2) characteristic not found!")
                return
            }

            val sbTx = svc.getCharacteristic(SpeedyBeeGatt.CHR_SB_TX)
            val sbRx = svc.getCharacteristic(SpeedyBeeGatt.CHR_SB_RX_NOTIFY)

            // Run handshake on a background thread (blocking waits for each step)
            thread(name = "handshake") {
                runHandshake(g, sbTx, sbRx, serialRx)
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            char: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (char.uuid) {
                SpeedyBeeGatt.CHR_SB_RX_NOTIFY -> {
                    // During handshake, collect response data
                    if (handshakePhase) {
                        synchronized(handshakeRespBuf) {
                            handshakeRespBuf.addAll(value.toList())
                        }
                        handshakeRespLatch?.countDown()
                    }
                }
                SpeedyBeeGatt.CHR_SERIAL_RX -> {
                    // BLE → UDP
                    sendUdp(value)
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write failed on ${char.uuid}: status=$status")
            }
            writeLatch?.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Descriptor write failed: status=$status")
            }
            descriptorLatch?.countDown()
        }
    }

    // ── Blocking GATT helpers (called from handshake thread) ─────────────────

    @SuppressLint("MissingPermission")
    private fun enableNotificationBlocking(g: BluetoothGatt, char: BluetoothGattCharacteristic): Boolean {
        g.setCharacteristicNotification(char, true)
        val desc = char.getDescriptor(SpeedyBeeGatt.CCC_DESCRIPTOR) ?: run {
            log("No CCC descriptor on ${char.uuid}")
            return false
        }
        descriptorLatch = CountDownLatch(1)
        g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        val ok = descriptorLatch!!.await(5, TimeUnit.SECONDS)
        if (!ok) log("Timeout enabling notifications on ${char.uuid}")
        else log("Enabled notifications on ${char.uuid}")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun writeCharBlocking(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    ): Boolean {
        writeLatch = CountDownLatch(1)
        g.writeCharacteristic(char, data, writeType)
        return writeLatch!!.await(5, TimeUnit.SECONDS)
    }

    /**
     * Write to ABF3 and wait for ABF4 response.
     * Returns collected response bytes, or empty if timeout.
     */
    @SuppressLint("MissingPermission")
    private fun sbWriteAndWait(
        g: BluetoothGatt,
        sbTx: BluetoothGattCharacteristic,
        packet: ByteArray,
        timeoutMs: Long = 5000
    ): ByteArray {
        synchronized(handshakeRespBuf) { handshakeRespBuf.clear() }
        handshakeRespLatch = CountDownLatch(1)
        log("  TX: ${packet.toHex()}")

        writeCharBlocking(g, sbTx, packet)

        handshakeRespLatch!!.await(timeoutMs, TimeUnit.MILLISECONDS)
        // Brief extra wait for multi-packet responses
        Thread.sleep(150)

        val resp: ByteArray
        synchronized(handshakeRespBuf) {
            resp = handshakeRespBuf.toByteArray()
        }
        log("  RX: ${resp.toHex()}")
        return resp
    }

    // ── Full v2 handshake ────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun runHandshake(
        g: BluetoothGatt,
        sbTx: BluetoothGattCharacteristic?,
        sbRx: BluetoothGattCharacteristic?,
        serialRx: BluetoothGattCharacteristic
    ) {
        if (sbTx != null && sbRx != null) {
            handshakePhase = true
            updateStatus("Handshake...")

            // Enable ABF4 notifications
            enableNotificationBlocking(g, sbRx)

            try {
                performHandshakeSteps(g, sbTx)
            } catch (e: Exception) {
                log("Handshake error: ${e.message}")
                updateStatus("Handshake failed: ${e.message}")
                handshakePhase = false
                return
            }

            handshakePhase = false
        } else {
            log("No proprietary chars (ABF3/ABF4), skipping handshake")
        }

        // Enable ABF2 notifications for serial data
        enableNotificationBlocking(g, serialRx)

        // MSP API_VERSION kick — some boards need this to start streaming
        log("Sending MSP API_VERSION kick")
        g.writeCharacteristic(
            serialTx!!,
            SpeedyBeeProto.MSP_API_VERSION,
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        )

        log("Bridge active")
        updateStatus("Bridge active")
    }

    private fun performHandshakeSteps(g: BluetoothGatt, sbTx: BluetoothGattCharacteristic) {
        // Step 1: Init — cmd=0x02, field1=3
        log("Handshake step 1: init")
        val initPkt = SpeedyBeeProto.sbPacket(0x02, field1 = 3)
        val initResp = sbWriteAndWait(g, sbTx, initPkt)

        if (initResp.isEmpty()) {
            log("No init response, continuing anyway")
            return
        }

        val respCmd = initResp[0].toInt() and 0xFF
        val respPayload = if (initResp.size > 3) initResp.copyOfRange(3, initResp.size) else byteArrayOf()
        val respFields = if (respPayload.isNotEmpty()) SpeedyBeeProto.decodeProto(respPayload) else emptyMap()
        log("  Init response: cmd=0x%02x fields=%s".format(respCmd, respFields))

        // Check if password is required (cmd=0x07 and field 2 present)
        val needsPassword = respCmd == 0x07 && 2 in respFields

        // Step 2: Password (if required)
        if (needsPassword) {
            val pw = password
            if (pw.isNullOrEmpty()) {
                throw RuntimeException("FC requires password. Set it in the Password field.")
            }
            log("Handshake step 2: password authentication")
            val pwPkt = SpeedyBeeProto.sbPacket(0x08, field1 = 4, field2 = pw.toByteArray(Charsets.US_ASCII))
            val pwResp = sbWriteAndWait(g, sbTx, pwPkt)

            if (pwResp.isNotEmpty()) {
                val pwCmd = pwResp[0].toInt() and 0xFF
                val pwPayload = if (pwResp.size > 3) pwResp.copyOfRange(3, pwResp.size) else byteArrayOf()
                val pwFields = if (pwPayload.isNotEmpty()) SpeedyBeeProto.decodeProto(pwPayload) else emptyMap()
                log("  Password response: cmd=0x%02x fields=%s".format(pwCmd, pwFields))
                if (pwCmd != 0x05) {
                    throw RuntimeException("Password rejected (cmd=0x%02x)".format(pwCmd))
                }
            } else {
                log("  No password response")
            }
        } else {
            log("  Password not required")
        }

        // Step 3: Serial number — cmd=0x0e, field1=13, field2=random serial
        val serial = ByteArray(5).also { SecureRandom().nextBytes(it) }.toHex()
        log("Handshake step 3: serial ($serial)")
        val serialPkt = SpeedyBeeProto.sbPacket(0x0e, field1 = 13, field2 = serial.toByteArray(Charsets.US_ASCII))
        val serialResp = sbWriteAndWait(g, sbTx, serialPkt)

        if (serialResp.size > 6) {
            log("  Device info: cmd=0x%02x, %d bytes".format(serialResp[0].toInt() and 0xFF, serialResp.size))
            parseDeviceInfo(serialResp)
        } else {
            log("  No device info response")
        }

        // Step 4: Session key — cmd=0x02, field1=0x2d
        log("Handshake step 4: session key")
        val keyPkt = SpeedyBeeProto.sbPacket(0x02, field1 = 0x2D)
        val keyResp = sbWriteAndWait(g, sbTx, keyPkt)

        if (keyResp.isNotEmpty()) {
            log("  Session key response: cmd=0x%02x, %d bytes".format(keyResp[0].toInt() and 0xFF, keyResp.size))
        }

        log("Handshake complete")
    }

    private fun parseDeviceInfo(data: ByteArray) {
        if (data.size < 10) return
        try {
            val raw = data.copyOfRange(minOf(6, data.size), data.size)
            val parts = raw.toString(Charsets.US_ASCII).split('\u0000')
            val strings = parts.filter { it.length > 2 }
            for (s in strings.take(5)) {
                log("  Device: $s")
            }
        } catch (_: Exception) {}
    }

    // ── UDP ──────────────────────────────────────────────────────────────────

    private fun startUdpListener(localPort: Int) {
        thread(isDaemon = true, name = "udp-rx") {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(localPort))
                }
                log("UDP listening on port $localPort")
                val buf = ByteArray(4096)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket?.receive(pkt)
                    if (!running) break
                    val data = buf.copyOf(pkt.length)
                    sendToBle(data)
                }
            } catch (e: Exception) {
                if (running) log("UDP error: ${e.message}")
            }
        }
    }

    private fun sendUdp(data: ByteArray) {
        val addr = udpRemoteAddr ?: return
        thread(isDaemon = true) {
            try {
                val pkt = DatagramPacket(data, data.size, addr, udpRemotePort)
                udpSocket?.send(pkt)
            } catch (e: Exception) {
                if (running) log("UDP send error: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendToBle(data: ByteArray) {
        val tx = serialTx ?: return
        val g = gatt ?: return
        val chunkSize = mtu - 3
        for (i in data.indices step chunkSize) {
            val end = minOf(i + chunkSize, data.size)
            val chunk = data.copyOfRange(i, end)
            g.writeCharacteristic(tx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, BridgeService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SpeedyBee BLE Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(Notification.Action.Builder(null, "Stop", stopPi).build())
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ── WakeLock ─────────────────────────────────────────────────────────────

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SpeedyBeeBLE::BridgeWakeLock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
