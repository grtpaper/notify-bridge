package com.example.notifybridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Restarts the bridge after a reboot or an app update if it was running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.running) return
        val sender = when (prefs.mode) {
            Prefs.MODE_SENDER -> true
            Prefs.MODE_RECEIVER -> false
            else -> return
        }
        // Android 14 crashes a connected-device service that starts without Bluetooth permission.
        if (!hasBluetoothPermissions(context, sender)) return
        val service = if (sender) SenderService::class.java else ReceiverService::class.java
        try {
            context.startForegroundService(Intent(context, service))
        } catch (e: Exception) {
            // Not allowed right now; the user can press Start in the app.
        }
    }

    private fun hasBluetoothPermissions(context: Context, sender: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val needed = ArrayList<String>()
        needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (sender) needed.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
}
