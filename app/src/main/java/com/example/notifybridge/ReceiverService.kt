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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid

/**
 * E-reader side. Finds the phone by scanning for its Bluetooth LE advertisement, connects directly
 * to the address the phone is using right now (phones change their address every few minutes),
 * subscribes to its notify characteristic, and turns every received message into a normal Android
 * notification that is also saved in the message list.
 *
 * If the phone is not found, scans repeat with a growing pause (3 seconds up to 3 minutes) so the
 * battery is spared. Switching Bluetooth off and on is noticed and the connection is rebuilt.
 *
 * With "security code" on, no Bluetooth pairing is needed: messages arrive encrypted and are
 * decrypted with the shared code.
 */
@SuppressLint("MissingPermission")
class ReceiverService : Service() {

    companion object {
        private const val NOTIF_ID = 2
        private const val SCAN_WINDOW_MS = 10_000L
        private const val SETUP_TIMEOUT_MS = 60_000L // includes time to accept a pairing request
        private val BACKOFF_MS = longArrayOf(3_000, 5_000, 10_000, 20_000, 40_000, 60_000, 120_000, 180_000)
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

    private val reconnect = Runnable { connect() }
    private val scanTimeout = Runnable { onScanWindowEnded() }
    private val setupTimeout = Runnable { onSetupTimedOut() }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> onBluetoothOff()
                BluetoothAdapter.STATE_ON -> {
                    log("Bluetooth turned on")
                    backoffStep = 0
                    main.removeCallbacks(reconnect)
                    main.postDelayed(reconnect, 1500)
                }
            }
        }
    }

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
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        backoffStep = 0
        log("Starting (${if (Prefs(this).useCode) "security code mode" else "pairing mode"})")
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        main.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // Not registered.
        }
        stopScan()
        closeGatt()
        log("Stopped")
        Bridge.receiverStatus = "Stopped"
        super.onDestroy()
    }

    private fun log(text: String) = AppLog.add(this, "R: $text")

    private fun onBluetoothOff() {
        log("Bluetooth turned off")
        main.post {
            if (stopping) return@post
            main.removeCallbacks(reconnect)
            main.removeCallbacks(setupTimeout)
            stopScan()
            closeGatt()
            subscribed = false
            setStatus("Bluetooth is off - turn it on")
            main.postDelayed(reconnect, 5000)
        }
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
        if (prefs.useCode && !Crypto.isValidCode(prefs.code)) {
            setStatus("Set the security code in Settings")
            main.postDelayed(reconnect, 5000)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off - turn it on")
            main.postDelayed(reconnect, 5000)
            return
        }
        startScan(prefs, adapter)
    }

    private fun closeGatt() {
        try {
            gatt?.close()
        } catch (e: Exception) {
            // Permission revoked or Bluetooth turned off; nothing left to close.
        }
        gatt = null
    }

    // ---- scanning ----

    private fun startScan(prefs: Prefs, adapter: BluetoothAdapter) {
        stopScan()
        closeGatt()
        subscribed = false
        val sc = adapter.bluetoothLeScanner
        if (sc == null) {
            setStatus("Bluetooth LE scanning is not available - retrying")
            log("No BLE scanner available")
            main.postDelayed(reconnect, 10_000)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        try {
            sc.startScan(listOf(filter), settings, scanCallback)
            scanner = sc
            scanning = true
            log("Scanning for ${prefs.peerName ?: "phone"}")
            setStatus("Looking for ${prefs.peerName ?: "phone"}...")
            main.postDelayed(scanTimeout, SCAN_WINDOW_MS)
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission missing")
            log("Bluetooth permission missing")
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
                log("Scan failed (code $errorCode)")
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
        log("Found ${name ?: "phone"} at ${result.device.address} (signal ${result.rssi}, saved pairing: ${bondText(result.device)})")
        connectTo(result.device, prefs.peerName ?: name ?: result.device.address)
    }

    private fun bondText(d: BluetoothDevice): String = when (d.bondState) {
        BluetoothDevice.BOND_BONDED -> "yes"
        BluetoothDevice.BOND_BONDING -> "pairing now"
        else -> "no"
    }

    private fun onScanWindowEnded() {
        if (!scanning || stopping) return
        stopScan()
        log("Phone not found in this scan")
        scheduleRetry("Phone not found")
    }

    private fun connectTo(device: BluetoothDevice, label: String) {
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
            log("Bluetooth permission missing")
        }
    }

    private fun onSetupTimedOut() {
        if (stopping || subscribed) return
        log("Connection setup timed out")
        closeGatt()
        scheduleRetry("Connection timed out")
    }

    /** Waits a growing amount of time (3 seconds up to 3 minutes) before looking for the phone again. */
    private fun scheduleRetry(note: String) {
        if (stopping) return
        main.removeCallbacks(setupTimeout)
        val delay = BACKOFF_MS[backoffStep.coerceAtMost(BACKOFF_MS.size - 1)]
        if (backoffStep < BACKOFF_MS.size - 1) backoffStep++
        setStatus("$note - trying again in ${delay / 1000}s")
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, delay)
    }

    // ---- GATT callbacks ----

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) {
                    g.close()
                    return@post
                }
                log("Connection state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    setStatus("Connected, setting up...")
                    if (!g.requestMtu(Protocol.REQUESTED_MTU)) g.discoverServices()
                } else {
                    g.close()
                    gatt = null
                    if (stopping) return@post
                    if (subscribed) {
                        // Was working and the link dropped: start looking again quickly.
                        subscribed = false
                        backoffStep = 0
                        setStatus("Disconnected - looking for phone...")
                        main.removeCallbacks(reconnect)
                        main.postDelayed(reconnect, 2_000)
                    } else {
                        if (status == 133) {
                            log("Status 133 usually means an old Bluetooth pairing is blocking the " +
                                "connection. Forget the phone in Bluetooth settings on both devices, " +
                                "or turn on the security code in Settings on both.")
                        }
                        scheduleRetry("Connection failed ($status)")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g !== gatt) return@post
                log("MTU $mtu (status $status)")
                g.discoverServices()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val ch = g.getService(Protocol.SERVICE_UUID)?.getCharacteristic(Protocol.NOTIFY_CHAR_UUID)
                val cccd = ch?.getDescriptor(Protocol.CCCD_UUID)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null || cccd == null) {
                    log("Phone service not found (status $status)")
                    setStatus("Phone service not found")
                    g.disconnect()
                    return@post
                }
                g.setCharacteristicNotification(ch, true)
                // In pairing mode, Android shows the pairing request here.
                writeCccd(g, cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    subscribed = true
                    backoffStep = 0
                    main.removeCallbacks(setupTimeout)
                    log("Subscribed to the phone")
                    setStatus("Connected to phone")
                } else {
                    log("Subscribe failed (status $status)")
                    closeGatt()
                    scheduleRetry("Subscribe failed ($status)")
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
            val message = readPayload(payload) ?: return@post
            log("Message received")
            History.add(this, message)
            showNotification(message)
        }
    }

    /** Turns the received bytes into a message, decrypting them if a security code is in use. */
    private fun readPayload(payload: ByteArray): Message? {
        val prefs = Prefs(this)
        val plain: ByteArray = if (Crypto.isEncrypted(payload)) {
            if (!prefs.useCode) {
                log("Phone sent an encrypted message but the security code is off here. Turn it on in Settings.")
                setStatus("Phone uses a security code - turn it on in Settings")
                return null
            }
            Crypto.decrypt(payload, prefs.code) ?: run {
                log("Could not decrypt a message: the security code is different on the phone")
                setStatus("Wrong security code")
                return null
            }
        } else {
            if (prefs.useCode) {
                log("Phone sent an unprotected message but the security code is on here. Turn it on on the phone too.")
                setStatus("Phone is not using the security code")
                return null
            }
            payload
        }
        val message = Protocol.decode(plain)
        if (message == null) log("Received a message that could not be read")
        return message
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
