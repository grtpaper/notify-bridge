package com.example.notifybridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid

/**
 * Phone side. Runs a BLE GATT server with one notify characteristic and advertises it.
 * The e-reader connects, subscribes, and receives every queued notification as BLE frames.
 *
 * The notification descriptor requires an encrypted link, so Android makes the e-reader pair with
 * the phone (the phone shows a pairing request) before it can subscribe.
 */
@SuppressLint("MissingPermission")
class SenderService : Service() {

    companion object {
        @Volatile
        var instance: SenderService? = null
        private const val NOTIF_ID = 1
        private const val MAX_PENDING = 30
        private const val MAX_ATTEMPTS = 3
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    private var stopping = false

    // Everything below is touched on the main thread only.
    private var target: BluetoothDevice? = null
    private var targetMtu = Protocol.DEFAULT_MTU
    private val mtus = HashMap<String, Int>()
    private val pending = ArrayDeque<ByteArray>()
    private var frames: List<ByteArray>? = null
    private var frameIndex = 0
    private var attempts = 0
    private var inFlight = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Channels.ensure(this)
        val n = buildNotification("Starting...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        startUp()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        instance = null
        main.removeCallbacksAndMessages(null)
        try {
            advertiser?.stopAdvertising(advCallback)
            server?.close()
        } catch (e: SecurityException) {
            // Permission revoked while running; nothing left to clean up.
        }
        server = null
        Bridge.senderStatus = "Stopped"
        super.onDestroy()
    }

    /** Called by NotificationForwarder (and the test button) from any thread. */
    fun enqueue(message: Message) {
        val payload = Protocol.encode(message)
        main.post {
            pending.addLast(payload)
            while (pending.size > MAX_PENDING) {
                // If the head is mid-send, drop the next-oldest instead.
                pending.removeAt(if (frames != null) 1 else 0)
            }
            pump()
        }
    }

    // ---- startup ----

    private fun startUp() {
        if (server != null || stopping) return
        val bm = getSystemService(BluetoothManager::class.java)
        val adapter = bm?.adapter
        if (bm == null || adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off - turn it on")
            main.postDelayed({ startUp() }, 5000)
            return
        }
        try {
            val srv = bm.openGattServer(this, serverCallback)
            if (srv == null) {
                setStatus("Could not open Bluetooth server")
                main.postDelayed({ startUp() }, 5000)
                return
            }
            val ch = BluetoothGattCharacteristic(
                Protocol.NOTIFY_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0
            )
            ch.addDescriptor(
                BluetoothGattDescriptor(
                    Protocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                        BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED
                )
            )
            val svc = BluetoothGattService(Protocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            svc.addCharacteristic(ch)
            notifyChar = ch
            server = srv
            srv.addService(svc)
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission missing")
        }
    }

    private fun startAdvertising(restart: Boolean) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            setStatus("This phone cannot advertise over Bluetooth LE")
            return
        }
        advertiser = adv
        try {
            if (restart) adv.stopAdvertising(advCallback)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
                .build()
            val scanResponse = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build()
            adv.startAdvertising(settings, data, scanResponse, advCallback)
        } catch (e: SecurityException) {
            setStatus("Bluetooth permission missing")
        }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            main.post { if (target == null) setStatus("Waiting for e-reader...") }
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return
            main.post { setStatus("Advertising failed (code $errorCode)") }
        }
    }

    // ---- GATT server ----

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising(false)
                else setStatus("Could not add Bluetooth service ($status)")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (target == null) setStatus("Device connected, waiting for it to subscribe...")
                } else {
                    mtus.remove(device.address)
                    if (device == target) clearTarget()
                    if (target == null) setStatus("Waiting for e-reader...")
                    startAdvertising(true)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post {
                mtus[device.address] = mtu
                if (device == target) targetMtu = mtu
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ) {
            val value = if (device == target) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            main.post {
                var result = BluetoothGatt.GATT_SUCCESS
                if (descriptor.uuid != Protocol.CCCD_UUID) {
                    result = BluetoothGatt.GATT_FAILURE
                } else if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    val current = target
                    if (current != null && current != device) {
                        // Only one e-reader at a time.
                        result = BluetoothGatt.GATT_FAILURE
                    } else {
                        target = device
                        targetMtu = mtus[device.address] ?: Protocol.DEFAULT_MTU
                        setStatus("E-reader connected")
                    }
                } else if (device == target) {
                    clearTarget()
                    setStatus("E-reader unsubscribed")
                }
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, result, 0, null)
                }
                if (result == BluetoothGatt.GATT_SUCCESS && target != null) {
                    // Give the e-reader a moment to finish enabling notifications.
                    main.postDelayed({ pump() }, 300)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                if (device != target) return@post
                inFlight = false
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    attempts = 0
                    frameIndex++
                    val f = frames
                    if (f != null && frameIndex >= f.size) {
                        pending.removeFirstOrNull()
                        frames = null
                        frameIndex = 0
                    }
                    pump()
                } else {
                    onSendFailed()
                }
            }
        }
    }

    // ---- sending ----

    private fun clearTarget() {
        target = null
        inFlight = false
        frames = null
        frameIndex = 0
        attempts = 0
    }

    /** Sends the next frame of the oldest pending notification, one frame in flight at a time. */
    private fun pump() {
        val dev = target ?: return
        if (inFlight) return
        val srv = server ?: return
        val ch = notifyChar ?: return
        if (frames == null) {
            val payload = pending.firstOrNull() ?: return
            frames = Protocol.frame(payload, targetMtu)
            frameIndex = 0
        }
        val f = frames ?: return
        val ok = try {
            sendFrame(srv, dev, ch, f[frameIndex])
        } catch (e: SecurityException) {
            false
        }
        if (ok) inFlight = true else onSendFailed()
    }

    private fun onSendFailed() {
        inFlight = false
        frames = null
        frameIndex = 0
        attempts++
        if (attempts >= MAX_ATTEMPTS) {
            pending.removeFirstOrNull()
            attempts = 0
        }
        main.postDelayed({ pump() }, 1000)
    }

    @Suppress("DEPRECATION")
    private fun sendFrame(
        srv: BluetoothGattServer,
        dev: BluetoothDevice,
        ch: BluetoothGattCharacteristic,
        data: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            srv.notifyCharacteristicChanged(dev, ch, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            ch.value = data
            srv.notifyCharacteristicChanged(dev, ch, false)
        }
    }

    // ---- status notification ----

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, Channels.STATUS)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Notify Bridge - sending")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun setStatus(text: String) {
        Bridge.senderStatus = text
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
