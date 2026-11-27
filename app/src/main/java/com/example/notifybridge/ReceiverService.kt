package com.example.notifybridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
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
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid

/**
 * E-reader side. Connects to the phone chosen on the main screen, subscribes to its notify
 * characteristic, and turns every received message into a normal Android notification.
 *
 * Two connection styles, chosen by the "Reconnect by scanning" setting:
 *
 * - Scanning (default): look for the phone's Bluetooth LE advertisement in short scans and connect
 *   directly to the address it is using right now. Phones change their Bluetooth address every few
 *   minutes, and some e-readers cannot match the new address to a saved pairing, so waiting for the
 *   saved pairing can hang forever. Scans repeat with a growing pause so the battery is spared.
 * - Classic (old behaviour): connectGatt with autoConnect to the saved pairing and wait.
 */
@SuppressLint("MissingPermission")
class ReceiverService : Service() {

    companion object {
        private const val NOTIF_ID = 2
        private const val SCAN_WINDOW_MS = 10_000L
        private const val SETUP_TIMEOUT_MS = 60_000L // includes time to accept a pairing request
        private val BACKOFF_MS = longArrayOf(3_000, 5_000, 10_000, 20_000, 40_000, 60_000, 120_000, 180_000)
        private const val FAILURES_BEFORE_REPAIR = 2

        // GATT status codes that mean the saved pairing keys are no longer accepted.
        private const val GATT_INSUFFICIENT_AUTHENTICATION = 5
        private const val GATT_INSUFFICIENT_ENCRYPTION = 15
        private const val GATT_AUTH_FAIL = 137
    }

    private val main = Handler(Looper.getMainLooper())
    private val reassembler = Reassembler()
    private var gatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var stopping = false
    private var nextId = 100

    private var subscribed = false
    private var backoffStep = 0
    private var failures = 0

    private val reconnect = Runnable { connect() }
    private val scanTimeout = Runnable { onScanWindowEnded() }
    private val setupTimeout = Runnable { onSetupTimedOut() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Channels.ensure(this)
        val n = buildStatusNotification("Starting...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        backoffStep = 0
        failures = 0
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        main.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        Bridge.receiverStatus = "Stopped"
        super.onDestroy()
    }

    // ---- connection ----

    private fun connect() {
        if (stopping) return
        main.removeCallbacks(reconnect)
        val prefs = Prefs(this)
        if (prefs.peerAddress == null) {
            setStatus("No phone selected - tap Find phone")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off - turn it on")
            main.postDelayed(reconnect, 5000)
            return
        }
        if (prefs.directConnect) startScan(prefs, adapter) else connectClassic(prefs, adapter)
    }

    private fun closeGatt() {
        try {
            gatt?.close()
        } catch (e: Exception) {
            // Permission revoked or Bluetooth turned off; nothing left to close.
        }
        gatt = null
    }

    /** Old behaviour: connect to the saved pairing with autoConnect and wait for the phone. */
    private fun connectClassic(prefs: Prefs, adapter: BluetoothAdapter) {
        val address = prefs.peerAddress ?: return
        try {
            var device = adapter.getRemoteDevice(address)
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                // The phone's address can change when pairing completes; look it up by name.
                val name = prefs.peerName
                val match = adapter.bondedDevices.firstOrNull { name != null && it.name == name }
                if (match != null) {
                    device = match
                    prefs.peerAddress = match.address
                }
            }
            closeGatt()
            reassembler.reset()
            setStatus("Connecting to ${prefs.peerName ?: device.address}...")
            val bonded = device.bondState == BluetoothDevice.BOND_BONDED
            gatt = device.connectGatt(this, bonded, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission missing")
        } catch (e: IllegalArgumentException) {
            setStatus("Saved phone address is invalid - tap Find phone")
        }
    }

    // ---- scanning ----

    private fun startScan(prefs: Prefs, adapter: BluetoothAdapter) {
        stopScan()
        closeGatt()
        subscribed = false
        val sc = adapter.bluetoothLeScanner
        if (sc == null) {
            setStatus("Bluetooth LE scanning is not available - retrying")
            main.postDelayed(reconnect, 10_000)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        try {
            sc.startScan(listOf(filter), settings, scanCallback)
            scanner = sc
            scanning = true
            setStatus("Looking for ${prefs.peerName ?: "phone"}...")
            main.postDelayed(scanTimeout, SCAN_WINDOW_MS)
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission missing")
        } catch (e: IllegalStateException) {
            // Bluetooth switched off while starting the scan.
            main.postDelayed(reconnect, 5_000)
        }
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (scanning) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                // Bluetooth turned off or permission revoked; the scan is already over.
            }
        }
        scanning = false
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { handleScanResult(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                if (stopping) return@post
                stopScan()
                scheduleRetry("Scan failed ($errorCode)")
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        if (!scanning || stopping) return
        val prefs = Prefs(this)
        val name = result.scanRecord?.deviceName
        val isOurPhone = result.device.address == prefs.peerAddress ||
            prefs.peerName == null || name == null || name == prefs.peerName
        if (!isOurPhone) return
        stopScan()
        connectDirect(result.device, prefs.peerName ?: name ?: result.device.address)
    }

    private fun onScanWindowEnded() {
        if (!scanning || stopping) return
        stopScan()
        scheduleRetry("Phone not found")
    }

    private fun connectDirect(device: BluetoothDevice, label: String) {
        closeGatt()
        reassembler.reset()
        subscribed = false
        setStatus("Connecting to $label...")
        try {
            gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
            main.removeCallbacks(setupTimeout)
            main.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission missing")
        }
    }

    private fun onSetupTimedOut() {
        if (stopping || subscribed) return
        val dev = gatt?.device
        closeGatt()
        linkFailed(dev, false, "Connection timed out")
    }

    /** Waits a growing amount of time (3 seconds up to 3 minutes) before looking for the phone again. */
    private fun scheduleRetry(note: String) {
        if (stopping) return
        val delay = BACKOFF_MS[backoffStep.coerceAtMost(BACKOFF_MS.size - 1)]
        if (backoffStep < BACKOFF_MS.size - 1) backoffStep++
        setStatus("$note - trying again in ${delay / 1000}s")
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delay)
    }

    /** A connection attempt failed before notifications were set up. Maybe repair the pairing. */
    private fun linkFailed(device: BluetoothDevice?, authProblem: Boolean, note: String) {
        main.removeCallbacks(setupTimeout)
        failures++
        val prefs = Prefs(this)
        if (prefs.autoFixPairing && (authProblem || failures >= FAILURES_BEFORE_REPAIR)) {
            val result = repairPairing(device, prefs.peerName)
            if (result != null) {
                failures = 0
                backoffStep = 0
                scheduleRetry(result)
                return
            }
        }
        scheduleRetry(note)
    }

    /**
     * Deletes the saved pairing with the phone so Android pairs again from scratch.
     * Returns a status message, or null if there was no saved pairing to remove.
     * Android hides this function from apps, so on some e-readers it does not work.
     */
    private fun repairPairing(device: BluetoothDevice?, phoneName: String?): String? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val targets = LinkedHashSet<BluetoothDevice>()
        if (device != null && device.bondState != BluetoothDevice.BOND_NONE) targets.add(device)
        try {
            adapter.bondedDevices?.filterTo(targets) { phoneName != null && it.name == phoneName }
        } catch (e: SecurityException) {
            return null
        }
        if (targets.isEmpty()) return null
        var removed = false
        for (d in targets) if (removeBond(d)) removed = true
        return if (removed) {
            "Removed old pairing, pairing again"
        } else {
            "Could not repair pairing: forget ${phoneName ?: "the phone"} in Bluetooth settings"
        }
    }

    private fun removeBond(d: BluetoothDevice): Boolean = try {
        d.javaClass.getMethod("removeBond").invoke(d) as? Boolean ?: false
    } catch (e: Exception) {
        false
    }

    // ---- GATT callbacks ----

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) {
                    g.close()
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("Connected, setting up...")
                    if (!g.requestMtu(Protocol.REQUESTED_MTU)) g.discoverServices()
                } else {
                    val dev = g.device
                    g.close()
                    gatt = null
                    if (stopping) return@post
                    if (Prefs(this@ReceiverService).directConnect) {
                        if (subscribed) {
                            // Was working and the link dropped: start over quickly.
                            subscribed = false
                            backoffStep = 0
                            failures = 0
                            setStatus("Disconnected - looking for phone...")
                            main.postDelayed(reconnect, 2_000)
                        } else {
                            linkFailed(dev, false, "Connection failed ($status)")
                        }
                    } else {
                        setStatus("Disconnected - reconnecting...")
                        main.postDelayed(reconnect, 3000)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post { if (g === gatt) g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val ch = g.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.NOTIFY_CHAR_UUID)
                val cccd = ch?.getDescriptor(Protocol.CCCD_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null || cccd == null) {
                    setStatus("Phone service not found")
                    g.disconnect()
                    return@post
                }
                g.setCharacteristicNotification(ch, true)
                // If the phone requires pairing, Android shows the pairing request here.
                writeCccd(g, cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val prefs = Prefs(this@ReceiverService)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    subscribed = true
                    failures = 0
                    backoffStep = 0
                    main.removeCallbacks(setupTimeout)
                    if (!prefs.directConnect) prefs.peerAddress = g.device.address
                    setStatus("Connected to phone")
                } else if (prefs.directConnect) {
                    val dev = g.device
                    closeGatt()
                    val authProblem = status == GATT_INSUFFICIENT_AUTHENTICATION ||
                        status == GATT_INSUFFICIENT_ENCRYPTION ||
                        status == GATT_AUTH_FAIL
                    linkFailed(dev, authProblem, "Pairing or subscribe failed ($status)")
                } else {
                    setStatus("Pairing or subscribe failed ($status)")
                    g.disconnect()
                }
            }
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleFrame(value)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value ?: return
            handleFrame(v)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeCccd(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }
    }

    // ---- incoming messages ----

    private fun handleFrame(frame: ByteArray) {
        val copy = frame.copyOf()
        main.post {
            val payload = reassembler.feed(copy) ?: return@post
            val message = Protocol.decode(payload) ?: return@post
            History.add(this, message)
            showNotification(message)
        }
    }

    private fun showNotification(m: Message) {
        val title = if (m.title.isBlank()) m.app else "${m.app}: ${m.title}"
        val builder = Notification.Builder(this, Channels.INCOMING)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setWhen(m.time)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
        if (m.text.isNotBlank()) {
            builder.setContentText(m.text)
            builder.setStyle(Notification.BigTextStyle().bigText(m.text))
        }
        getSystemService(NotificationManager::class.java).notify(nextId++, builder.build())
    }

    // ---- status notification ----

    private fun buildStatusNotification(text: String): Notification =
        Notification.Builder(this, Channels.STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Notify Bridge - receiving")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun setStatus(text: String) {
        Bridge.receiverStatus = text
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatusNotification(text))
    }
}
