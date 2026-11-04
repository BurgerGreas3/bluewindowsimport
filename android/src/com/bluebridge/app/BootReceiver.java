package com.bluebridge.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * BootReceiver — auto-starts BridgeService after device boot,
 * app update, or quick-boot (Huawei/Xiaomi FastBoot).
 * This ensures the Blue Bluetooth listener is always alive
 * even without the user opening the app.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !"android.intent.action.LOCKED_BOOT_COMPLETED".equals(action)) {
            Log.d(TAG, "Ignoring unrelated broadcast: " + action);
            return;
        }
        Log.d(TAG, "Boot/Update received: " + action);

        Intent serviceIntent = new Intent(context, BridgeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            Log.d(TAG, "BridgeService started from BootReceiver.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BridgeService: " + e.getMessage());
        }
    }
}
