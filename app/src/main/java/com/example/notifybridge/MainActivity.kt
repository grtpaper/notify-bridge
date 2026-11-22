package com.example.notifybridge

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One screen for both roles. The first time the app opens you pick whether this device is the
 * phone (sender) or the e-reader (receiver). The UI is plain black-on-white for e-ink screens.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMS = 1
    }

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var statusView: TextView? = null
    private var startStopButton: Button? = null
    private var foundAdapter: ArrayAdapter<String>? = null
    private var messagesAdapter: ArrayAdapter<String>? = null
    private var shownHistoryVersion = -1
    private var receiverSetup: Boolean? = null // null = decide automatically
    private var showingSetup = false
    private var scanner: BluetoothLeScanner? = null
    private var pendingAction: (() -> Unit)? = null
    private val found = LinkedHashMap<String, String>() // address -> name
    private val ui = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            if (messagesAdapter != null && History.version != shownHistoryVersion) refreshMessages()
            ui.postDelayed(this, 1000)
        }
    }
    private val stopScanRunnable = Runnable { stopScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
        ui.post(tick)
    }

    override fun onPause() {
        stopScan()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    // ---- screens ----

    private fun render() {
        stopScan()
        root.removeAllViews()
        statusView = null
        startStopButton = null
        foundAdapter = null
        messagesAdapter = null
        root.addView(label("Notify Bridge", 24f, bold = true))
        when (prefs.mode) {
            Prefs.MODE_SENDER -> renderSender()
            Prefs.MODE_RECEIVER -> renderReceiver()
            else -> renderChooser()
        }
        updateStatus()
    }

    private fun renderChooser() {
        root.addView(label("Install this app on both devices, then choose the role of this one."))
        root.addView(button("This is my PHONE (sends notifications)") {
            prefs.mode = Prefs.MODE_SENDER
            render()
        })
        root.addView(button("This is my E-READER (shows notifications)") {
            prefs.mode = Prefs.MODE_RECEIVER
            render()
        })
    }

    private fun renderSender() {
        root.addView(label("Role: phone (sender)", bold = true))
        statusView = label("").also { root.addView(it) }
        root.addView(button("1. Allow notification access") { openListenerSettings() })
        root.addView(button("2. Allow background running") { openBatterySettings() })
        startStopButton = button("3. Start") { toggleBridge() }.also { root.addView(it) }
        root.addView(CheckBox(this).apply {
            text = "Send message text (off = app name and title only)"
            setTextColor(Color.BLACK)
            isChecked = prefs.includeText
            setOnCheckedChangeListener { _, checked -> prefs.includeText = checked }
        })
        root.addView(button("Send test notification") { sendTest() })
        root.addView(label("Apps to forward (tap to choose):", bold = true))
        addAppList()
        root.addView(button("Change role") { changeRole() })
    }

    private fun renderReceiver() {
        val setup = receiverSetup ?: (prefs.peerAddress == null)
        if (setup) renderReceiverSetup() else renderMessages()
    }

    private fun renderMessages() {
        showingSetup = false
        root.addView(label("Role: e-reader (receiver)", bold = true))
        statusView = label("").also { root.addView(it) }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(
            button("Setup") {
                receiverSetup = true
                render()
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            button("Clear all") { confirmClear() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        root.addView(row)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        messagesAdapter = adapter
        val list = ListView(this)
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        refreshMessages()
    }

    private fun renderReceiverSetup() {
        showingSetup = true
        root.addView(label("Role: e-reader (receiver)", bold = true))
        statusView = label("").also { root.addView(it) }
        root.addView(label("First open Notify Bridge on the phone and press Start there."))
        root.addView(button("1. Find phone") { startScan() })
        root.addView(button("2. Allow background running") { openBatterySettings() })
        startStopButton = button("3. Start") { toggleBridge() }.also { root.addView(it) }
        root.addView(label("Phones found (tap yours):", bold = true))
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        foundAdapter = adapter
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> pickPhone(position) }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(button("Back to messages") {
            receiverSetup = false
            render()
        })
        root.addView(button("Change role") { changeRole() })
    }

    private fun addAppList() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val list = ListView(this)
        list.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        list.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            apps.map { it.second }
        )
        val allowed = prefs.allowed
        apps.forEachIndexed { i, app -> list.setItemChecked(i, app.first in allowed) }
        list.setOnItemClickListener { _, _, position, _ ->
            val set = prefs.allowed.toMutableSet()
            if (list.isItemChecked(position)) set.add(apps[position].first) else set.remove(apps[position].first)
            prefs.allowed = set
        }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ---- start / stop ----

    private fun toggleBridge() {
        if (prefs.running) stopBridge() else startBridge()
    }

    private fun startBridge() {
        withPermissions {
            prefs.running = true
            val service = if (prefs.mode == Prefs.MODE_SENDER) {
                SenderService::class.java
            } else {
                ReceiverService::class.java
            }
            try {
                startForegroundService(Intent(this, service))
            } catch (e: Exception) {
                toast("Could not start: ${e.message}")
            }
            refreshStartStop()
        }
    }

    private fun stopBridge() {
        prefs.running = false
        stopService(Intent(this, SenderService::class.java))
        stopService(Intent(this, ReceiverService::class.java))
        refreshStartStop()
    }

    private fun changeRole() {
        stopBridge()
        prefs.mode = null
        receiverSetup = null
        render()
    }

    private fun sendTest() {
        val service = SenderService.instance
        if (service == null) {
            toast("Press Start first")
            return
        }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
        service.enqueue(
            Message("Notify Bridge", "Test notification", "Sent from your phone at $time", System.currentTimeMillis())
        )
        toast("Test queued")
    }

    // ---- e-reader: find and pick the phone ----

    private fun startScan() {
        withPermissions {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                toast("Turn Bluetooth on first")
                return@withPermissions
            }
            val sc = adapter.bluetoothLeScanner
            if (sc == null) {
                toast("Bluetooth LE scanning is not available")
                return@withPermissions
            }
            stopScan()
            found.clear()
            refreshFound()
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                sc.startScan(listOf(filter), settings, scanCallback)
                scanner = sc
                ui.postDelayed(stopScanRunnable, 15000)
                toast("Scanning for 15 seconds...")
            } catch (e: SecurityException) {
                toast("Bluetooth permission missing")
            }
        }
    }

    private fun stopScan() {
        ui.removeCallbacks(stopScanRunnable)
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // Bluetooth turned off or permission revoked; nothing to stop.
        }
        scanner = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val name = result.scanRecord?.deviceName ?: deviceName(result.device) ?: "Unknown phone"
            runOnUiThread { if (found.put(address, name) == null) refreshFound() }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { toast("Scan failed (code $errorCode)") }
        }
    }

    private fun deviceName(d: BluetoothDevice): String? = try {
        d.name
    } catch (e: SecurityException) {
        null
    }

    private fun refreshFound() {
        val a = foundAdapter ?: return
        a.clear()
        a.addAll(found.map { "${it.value}  (${it.key})" })
        a.notifyDataSetChanged()
    }

    private fun pickPhone(position: Int) {
        val entry = found.entries.elementAtOrNull(position) ?: return
        prefs.peerAddress = entry.key
        prefs.peerName = entry.value
        stopScan()
        toast("Selected ${entry.value}. Accept the pairing request on both devices.")
        startBridge()
    }

    // ---- permissions and settings screens ----

    private fun requiredPermissions(): List<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
            list.add(
                if (prefs.mode == Prefs.MODE_SENDER) Manifest.permission.BLUETOOTH_ADVERTISE
                else Manifest.permission.BLUETOOTH_SCAN
            )
        } else if (prefs.mode == Prefs.MODE_RECEIVER) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION) // needed to scan on Android 11 and older
        }
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list
    }

    private fun missingPermissions(): List<String> =
        requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Runs [action] once the needed permissions are granted (asking first if necessary). */
    private fun withPermissions(action: () -> Unit) {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val action = pendingAction
        pendingAction = null
        // Missing notification permission is not a blocker; Bluetooth permissions are.
        val blocking = missingPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (blocking.isEmpty()) action?.invoke() else toast("Bluetooth permission is required")
    }

    private fun openListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            toast("Open Settings > Notifications > Notification access")
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("Open Settings > Battery and let this app run in the background")
            }
        }
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    // ---- status ----

    private fun refreshStartStop() {
        startStopButton?.text = if (prefs.running) "3. Stop" else "3. Start"
    }

    private fun updateStatus() {
        refreshStartStop()
        val view = statusView ?: return
        val sb = StringBuilder()
        when (prefs.mode) {
            Prefs.MODE_SENDER -> {
                sb.append("Notification access: ")
                    .append(if (isListenerEnabled()) "on" else "OFF - do step 1")
                    .append('\n')
                    .append("Status: ")
                    .append(if (prefs.running) Bridge.senderStatus else "stopped")
                if (prefs.allowed.isEmpty()) sb.append("\nNo apps chosen yet - pick apps below")
            }
            Prefs.MODE_RECEIVER -> {
                sb.append("Phone: ")
                    .append(prefs.peerName ?: "not selected")
                    .append('\n')
                    .append("Status: ")
                    .append(if (prefs.running) Bridge.receiverStatus else "stopped")
                if (showingSetup && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    sb.append("\nNotifications are blocked - allow them in Android settings")
                }
            }
        }
        view.text = sb.toString()
    }

    // ---- e-reader: saved messages ----

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Delete all saved messages?")
            .setPositiveButton("Delete") { _, _ ->
                History.clear(this)
                refreshMessages()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshMessages() {
        val a = messagesAdapter ?: return
        val items = History.all(this)
        shownHistoryVersion = History.version
        a.clear()
        if (items.isEmpty()) {
            a.add("No messages yet.")
        } else {
            a.addAll(items.map { formatMessage(it) })
        }
        a.notifyDataSetChanged()
    }

    private fun formatMessage(m: Message): String {
        val head = if (m.title.isBlank()) m.app else "${m.app}: ${m.title}"
        val first = "${formatTime(m.time)}  $head"
        return if (m.text.isBlank()) first else "$first\n${m.text}"
    }

    private fun formatTime(millis: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance()
        then.timeInMillis = millis
        val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "d MMM HH:mm"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }

    // ---- small view helpers ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun label(text: String, size: Float = 16f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.BLACK)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
        }
}
