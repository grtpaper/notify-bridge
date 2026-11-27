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
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One screen for both roles. The first time the app opens you pick whether this device is the
 * phone (sender) or the e-reader (receiver). The UI is plain black-on-white for e-ink screens.
 * A Settings screen holds two options: a shared security code (instead of Bluetooth pairing) and
 * a log screen for troubleshooting.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMS = 1
        const val SCREEN_MAIN = 0
        const val SCREEN_SETTINGS = 1
        const val SCREEN_LOG = 2
    }

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private var screen = SCREEN_MAIN
    private var statusView: TextView? = null
    private var codeView: TextView? = null
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            SCREEN_LOG -> {
                screen = SCREEN_SETTINGS
                render()
            }
            SCREEN_SETTINGS -> {
                screen = SCREEN_MAIN
                render()
            }
            else -> super.onBackPressed()
        }
    }

    // ---- screens ----

    private fun render() {
        stopScan()
        root.removeAllViews()
        statusView = null
        codeView = null
        startStopButton = null
        foundAdapter = null
        messagesAdapter = null
        root.addView(label("Notify Bridge", 24f, bold = true))
        if (prefs.mode == null) {
            renderChooser()
        } else {
            when (screen) {
                SCREEN_SETTINGS -> renderSettings()
                SCREEN_LOG -> renderLog()
                else -> if (prefs.mode == Prefs.MODE_SENDER) renderSender() else renderReceiver()
            }
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
        root.addView(buttonRow("Settings" to { openSettings() }, "Change role" to { changeRole() }))
    }

    private fun renderReceiver() {
        val setup = receiverSetup ?: (prefs.peerAddress == null)
        if (setup) renderReceiverSetup() else renderMessages()
    }

    private fun renderMessages() {
        showingSetup = false
        root.addView(label("Role: e-reader (receiver)", bold = true))
        statusView = label("").also { root.addView(it) }
        root.addView(
            buttonRow(
                "Setup" to {
                    receiverSetup = true
                    render()
                },
                "Settings" to { openSettings() },
                "Clear all" to { confirmClear() }
            )
        )
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
        root.addView(
            buttonRow(
                "Back to messages" to {
                    receiverSetup = false
                    render()
                },
                "Settings" to { openSettings() }
            )
        )
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

    // ---- settings screen ----

    private fun openSettings() {
        screen = SCREEN_SETTINGS
        render()
    }

    private fun renderSettings() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(box)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        box.addView(label("Settings", 20f, bold = true))

        box.addView(label("Security code", bold = true))
        box.addView(
            label(
                "Use a secret code instead of Bluetooth pairing. Turn this on and enter the same code on " +
                    "both the phone and the e-reader. Messages are encrypted with the code, so nothing is " +
                    "paired and old pairings stop mattering. Use at least ${Crypto.MIN_CODE_LENGTH} " +
                    "characters; longer and less guessable is safer.",
                14f
            )
        )
        box.addView(CheckBox(this).apply {
            text = "Use a security code instead of Bluetooth pairing"
            setTextColor(Color.BLACK)
            isChecked = prefs.useCode
            setOnCheckedChangeListener { _, checked ->
                prefs.useCode = checked
                if (checked && !Crypto.isValidCode(prefs.code)) toast("Now set the security code")
                restartBridge()
            }
        })
        codeView = label(codeStatusText()).also { box.addView(it) }
        box.addView(button("Set security code") { askCode() })

        box.addView(label("Log screen", bold = true))
        box.addView(
            label(
                "Records connection steps (scans, errors, pairing, messages sent or received) so problems " +
                    "can be found. Turn it on only while troubleshooting.",
                14f
            )
        )
        box.addView(CheckBox(this).apply {
            text = "Record a log and show the log screen"
            setTextColor(Color.BLACK)
            isChecked = prefs.logEnabled
            setOnCheckedChangeListener { _, checked ->
                prefs.logEnabled = checked
                render()
            }
        })
        if (prefs.logEnabled) {
            box.addView(button("View log") {
                screen = SCREEN_LOG
                render()
            })
        }
        box.addView(button("Back") {
            screen = SCREEN_MAIN
            render()
        })
    }

    private fun codeStatusText(): String =
        if (Crypto.isValidCode(prefs.code)) "Security code: set (${prefs.code.length} characters)"
        else "Security code: not set"

    private fun askCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "At least ${Crypto.MIN_CODE_LENGTH} characters"
            setText(prefs.code)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Security code")
            .setMessage("Enter the same code on the phone and the e-reader.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (!Crypto.isValidCode(value)) {
                    toast("Too short: use at least ${Crypto.MIN_CODE_LENGTH} characters")
                } else {
                    prefs.code = value
                    codeView?.text = codeStatusText()
                    toast("Code saved")
                    restartBridge()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- log screen ----

    private fun renderLog() {
        root.addView(label("Log", 20f, bold = true))
        val text = AppLog.read(this)
        val body = TextView(this).apply {
            this.text = if (text.isBlank()) "(empty - lines appear while the bridge runs)" else text
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        root.addView(
            buttonRow(
                "Refresh" to { render() },
                "Copy" to { copyLog() },
                "Share" to { shareLog() }
            )
        )
        root.addView(
            buttonRow(
                "Save file" to { saveLogToDownloads() },
                "Clear" to {
                    AppLog.clear(this)
                    render()
                },
                "Back" to {
                    screen = SCREEN_SETTINGS
                    render()
                }
            )
        )
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Notify Bridge log", AppLog.read(this)))
        toast("Log copied")
    }

    private fun shareLog() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Notify Bridge log")
            .putExtra(Intent.EXTRA_TEXT, AppLog.read(this).takeLast(40_000))
        try {
            startActivity(Intent.createChooser(intent, "Share log"))
        } catch (e: ActivityNotFoundException) {
            toast("No app to share with. Use Copy or Save file.")
        }
    }

    /** Saves the log as a text file in the Downloads folder (Android 10 and newer). */
    private fun saveLogToDownloads() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Saving a file needs Android 10 or newer. Use Copy or Share.")
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "notify-bridge-log-${System.currentTimeMillis() / 1000}.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                toast("Could not save the file")
                return
            }
            contentResolver.openOutputStream(uri)?.use { it.write(AppLog.read(this).toByteArray()) }
            toast("Saved in the Downloads folder")
        } catch (e: Exception) {
            toast("Could not save: ${e.message}")
        }
    }

    // ---- start / stop ----

    private fun toggleBridge() {
        if (prefs.running) stopBridge() else startBridge()
    }

    private fun startBridge() {
        if (prefs.useCode && !Crypto.isValidCode(prefs.code)) {
            toast("Set the security code first (Settings)")
            return
        }
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

    /** If the bridge is running, restart it so a changed setting (code on/off, new code) applies. */
    private fun restartBridge() {
        if (!prefs.running) return
        stopService(Intent(this, SenderService::class.java))
        stopService(Intent(this, ReceiverService::class.java))
        ui.postDelayed({
            if (!prefs.running) return@postDelayed
            val service = if (prefs.mode == Prefs.MODE_SENDER) {
                SenderService::class.java
            } else {
                ReceiverService::class.java
            }
            try {
                startForegroundService(Intent(this, service))
            } catch (e: Exception) {
                toast("Press Stop and Start to apply the change")
            }
        }, 700)
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
        screen = SCREEN_MAIN
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
        if (prefs.useCode) {
            toast("Selected ${entry.value}.")
        } else {
            toast("Selected ${entry.value}. Accept the pairing request on both devices.")
        }
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

    private fun protectionText(): String {
        if (!prefs.useCode) return "Protection: Bluetooth pairing"
        return if (Crypto.isValidCode(prefs.code)) "Protection: security code"
        else "Protection: security code NOT SET - open Settings"
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
                    .append(protectionText())
                    .append('\n')
                    .append("Status: ")
                    .append(if (prefs.running) Bridge.senderStatus else "stopped")
                if (prefs.allowed.isEmpty()) sb.append("\nNo apps chosen yet - pick apps below")
            }
            Prefs.MODE_RECEIVER -> {
                sb.append("Phone: ")
                    .append(prefs.peerName ?: "not selected")
                    .append('\n')
                    .append(protectionText())
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

    /** Buttons side by side, sharing the width equally. */
    private fun buttonRow(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for ((text, action) in items) {
            row.addView(
                button(text, action),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        return row
    }
}
