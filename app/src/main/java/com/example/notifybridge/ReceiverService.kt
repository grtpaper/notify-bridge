package com.example.notifybridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * E-reader side. Connects to the phone chosen on the main screen, subscribes to its notify
 * characteristic, and turns every received message into a normal Android notification.
 *
 * Once the phone is paired, the connection is made with autoConnect so Android reconnects on its
 * own whenever the phone is back in range, without the e-reader scanning continuously.
 */
@SuppressLint("MissingPermission")
class ReceiverService : Service() {

    companion object {
        private const val NOTIF_ID = 2
    }

    private val main = Handler(Looper.getMainLooper())
    private val reassembler = Reassembler()
    private var gatt: BluetoothGatt? = null
    private var stopping = false
    private var nextId = 100

    private val reconnect = Runnable { connect() }

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
        connect()
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        main.removeCallbacksAndMessages(null)
        try {
            gatt?.close()
        } catch (e: SecurityException) {
            // Permission revoked while running.
        }
        gatt = null
        Bridge.receiverStatus = "Stopped"
        super.onDestroy()
    }

    // ---- connection ----

    private fun connect() {
        if (stopping) return
        main.removeCallbacks(reconnect)
        val prefs = Prefs(this)
        val address = prefs.peerAddress
        if (address == null) {
            setStatus("No phone selected - tap Find phone")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus("Bluetooth is off - turn it on")
            main.postDelayed(reconnect, 5000)
            return
        }
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
            gatt?.close()
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
                    g.close()
                    gatt = null
                    if (!stopping) {
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
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Prefs(this@ReceiverService).peerAddress = g.device.address
                    setStatus("Connected to phone")
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
