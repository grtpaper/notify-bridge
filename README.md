# Notify Bridge

Forwards notifications from your Android phone to your Android e-reader over Bluetooth LE,
so you see them while reading. One app, installed on both devices; each device picks a role.

- **Phone (sender)**: reads notifications from the apps you choose and advertises a small
  BLE service. It is the BLE peripheral, because phones reliably support that.
- **E-reader (receiver)**: connects to the phone as a BLE central, subscribes, and shows each
  message as a normal high-priority Android notification.

No internet, accounts, or third-party libraries are used. Notifications travel only over the
paired, encrypted Bluetooth link between your two devices.

## Build the APK

You need Android Studio (any recent version) with the Android SDK installed.

1. Open this folder (`NotifyBridge`) in Android Studio and let Gradle sync.
2. Menu: **Build > Build APK(s)**. Android Studio shows a link to the file when done.
   Or from a terminal: `./gradlew assembleDebug`
3. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

Copy that one file to both devices and install it (allow "install unknown apps" for whatever
app you open it from), or use `adb install app-debug.apk` for each.

## Build without Android Studio (GitHub, free)

1. Create a GitHub account and a new repository (public is fine; there are no secrets here).
2. Upload everything in this folder to it (Add file > Upload files). The workflow must end up at
   `.github/workflows/build.yml`. If the `.github` folder did not upload, choose Add file >
   Create new file, type `.github/workflows/build.yml` as the name, and paste the contents of
   `build-workflow.yml` from this folder.
3. Open the **Actions** tab. The "Build APK" run starts by itself (or press Run workflow).
4. When it turns green (about 5 to 10 minutes), open the run and download **NotifyBridge-apk**
   from the Artifacts section. Unzip it to get `app-debug.apk`.

## Set up

**Phone**
1. Open Notify Bridge, choose **This is my PHONE**.
2. Step 1: turn on notification access for Notify Bridge.
   On Android 13 and newer, an APK installed outside the Play Store has this switch greyed out.
   Fix: Settings > Apps > Notify Bridge > three-dot menu > **Allow restricted settings**, then retry.
3. Step 2: allow background running. Step 3: Start.
4. Tick the apps whose notifications you want on the e-reader.

**E-reader**
1. Open Notify Bridge, choose **This is my E-READER**.
2. Make sure the phone shows "Waiting for e-reader..." (phone Start pressed).
3. Press **Find phone**, tap your phone in the list. Accept the pairing request on **both** devices.
4. Step 2: allow background running. Step 3: Start (if it is not already running).

Then press **Send test notification** on the phone. After the first pairing, the e-reader
reconnects on its own whenever the phone is in range, including after a reboot.

## Troubleshooting

- **Nothing found when scanning (e-reader on Android 11 or older)**: Android requires the
  Location permission and the Location switch to be on for Bluetooth scanning.
- **Stuck on "Connecting..." after a failed pairing**: press Find phone again. If it keeps failing,
  "Forget" the other device in Android's Bluetooth settings on both devices and pair again.
- **Notification shows in the e-reader's notification shade but not on top of the book**: some
  e-ink launchers hide pop-ups inside the reader app. Check the e-reader's notification and
  "floating window" settings for this app.
- **Messages stop arriving after a while**: e-readers often have aggressive power saving.
  Set battery to "Unrestricted" or "No restrictions" for Notify Bridge on both devices.
- **"This phone cannot advertise over Bluetooth LE"**: that phone's hardware does not support
  BLE peripheral mode. Swap roles only if the e-reader supports it (not implemented here).

## Privacy

Only apps you tick are forwarded. Ongoing notifications (music players, downloads) and group
summaries are skipped. Untick "Send message text" to forward only the app name and title.
The phone only delivers messages to a device that completed Bluetooth pairing, and Android
shows a pairing request on the phone that you must accept. Accept it only for your e-reader.

## Protocol (for hacking on it)

Service `7e1c0001-5b2a-4d2e-9c3f-2f6f1a9b0c01`, notify characteristic
`7e1c0002-5b2a-4d2e-9c3f-2f6f1a9b0c01`. Each notification is a UTF-8 JSON object
`{"app","title","text","t"}` split into frames; the first byte of each frame is 0 (more follows)
or 1 (last frame).
