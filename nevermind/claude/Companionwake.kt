/*
 * CompanionWake.kt — give the peer app a wake path the freezer actually honours.
 *
 * WHY THIS AND NOT A BIGGER FOREGROUND SERVICE
 * An inbound RFCOMM connection is handled entirely inside the Bluetooth stack
 * and the kernel. It never reaches ActivityManagerService as an app lifecycle
 * event, so it can never unfreeze a frozen process. That is exactly the
 * signature you captured: fast refusal on every channel, zero framed ACKs.
 * No amount of listener hardening on the app side changes this, because the
 * app gets no CPU at all.
 *
 * A CompanionDeviceService bind IS a lifecycle event. The system binds it when
 * the associated classic-Bluetooth device connects and unbinds it when that
 * connection drops. That bind is what thaws the process; only then is there
 * anything alive to accept on channel 25.
 *
 * SEQUENCE THE MAC SHOULD DRIVE
 *   1. Open any Bluetooth connection to the phone (an ACL/L2CAP connect to the
 *      bonded device is enough; it does not have to be your RFCOMM channel).
 *   2. Wait for EVENT_BT_CONNECTED to land here -> app thaws -> FGS starts ->
 *      listener is (re)opened.
 *   3. Only now attempt the RFCOMM open on channel 25. Budget ~2-4 s between
 *      step 1 and step 3; do not burn the 12-attempt probing loop before it.
 *
 * AndroidManifest.xml
 *   <uses-feature android:name="android.software.companion_device_setup" />
 *   <uses-permission android:name="android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND" />
 *   <uses-permission android:name="android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" />
 *   <uses-permission android:name="android.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
 *   <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
 *   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
 *
 *   <service
 *       android:name=".CompanionWakeService"
 *       android:exported="true"
 *       android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE">
 *       <intent-filter>
 *           <action android:name="android.companion.CompanionDeviceService" />
 *       </intent-filter>
 *   </service>
 *
 * REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND is the one that
 * lets step 2 legally start the connectedDevice FGS from the background; prefer
 * it over REQUEST_COMPANION_RUN_IN_BACKGROUND where both would do.
 */

package com.bluebridge.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import java.util.regex.Pattern

private const val TAG = "CompanionWake"

/* ------------------------------------------------------------------ */
/* 1. One-time association. Run from an Activity; shows a system chooser. */
/* ------------------------------------------------------------------ */

class AssociationHelper(private val activity: ComponentActivity) {

    private val cdm by lazy { activity.getSystemService(CompanionDeviceManager::class.java) }

    private val chooser = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val info = result.data?.getParcelableExtra(
            CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java
        )
        if (info != null) startObserving(info.id)
    }

    /** macName: the MacBook's Bluetooth name, e.g. "Kim's MacBook Air". */
    fun associate(macName: String) {
        val filter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(Pattern.quote(macName)))
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build()

        cdm.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: android.content.IntentSender) {
                chooser.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                startObserving(associationInfo.id)
            }

            override fun onFailure(error: CharSequence?) {
                Log.e(TAG, "association failed: $error")
            }
        })
    }

    /** Idempotent; safe to call on every cold start and from BOOT_COMPLETED. */
    @SuppressLint("MissingPermission")
    fun startObserving(associationId: Int) {
        if (Build.VERSION.SDK_INT >= 36) {
            cdm.startObservingDevicePresence(
                ObservingDevicePresenceRequest.Builder()
                    .setAssociationId(associationId)
                    .build()
            )
        } else {
            // API 31-35 path: address-based observation.
            val address = cdm.myAssociations.firstOrNull { it.id == associationId }
                ?.deviceMacAddress?.toString() ?: return
            @Suppress("DEPRECATION")
            cdm.startObservingDevicePresence(address)
        }
        Log.i(TAG, "observing presence for association $associationId")
    }
}

/* ------------------------------------------------------------------ */
/* 2. The service the system binds. This bind is what unfreezes you.   */
/* ------------------------------------------------------------------ */

class CompanionWakeService : CompanionDeviceService() {

    /** API 36+. Fires for BT connect/disconnect as well as BLE range events. */
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED,
            DevicePresenceEvent.EVENT_BLE_APPEARED -> onPeerPresent()

            DevicePresenceEvent.EVENT_BT_DISCONNECTED,
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> onPeerGone()
        }
    }

    @Deprecated("Pre-API-36 callback; keep for ColorOS builds on Android 12-15.")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = onPeerPresent()

    @Deprecated("Pre-API-36 callback; keep for ColorOS builds on Android 12-15.")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) = onPeerGone()

    private fun onPeerPresent() {
        Log.i(TAG, "peer present -> starting listener FGS")
        // Legal from the background because of
        // REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND.
        val intent = Intent(this, RfcommListenerService::class.java)
            .setAction(RfcommListenerService.ACTION_ENSURE_LISTENING)
        startForegroundService(intent)
    }

    private fun onPeerGone() {
        Log.i(TAG, "peer gone -> releasing listener")
        startService(
            Intent(this, RfcommListenerService::class.java)
                .setAction(RfcommListenerService.ACTION_RELEASE)
        )
    }
}

/*
 * RfcommListenerService is your existing connectedDevice foreground service.
 * Two changes to make it cooperate with the above:
 *
 *   - ACTION_ENSURE_LISTENING must be idempotent: if the BluetoothServerSocket
 *     is already open and the accept thread alive, do nothing and return. It
 *     will be delivered on every reconnect.
 *   - On thaw, close and reopen the server socket rather than trusting the one
 *     that existed before the freeze. A socket whose owner missed an unknown
 *     number of events is not worth trusting; reopening costs milliseconds.
 */