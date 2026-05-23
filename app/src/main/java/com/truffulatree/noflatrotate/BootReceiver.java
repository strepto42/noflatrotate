package com.truffulatree.noflatrotate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

/**
 * Receives BOOT_COMPLETED (and MY_PACKAGE_REPLACED after app updates) and
 * starts the RotationService when the user has the service enabled, has
 * opted into start-on-boot, and has granted WRITE_SETTINGS.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "Received " + action);

        SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        boolean startOnBoot = prefs.getBoolean(MainActivity.KEY_START_ON_BOOT, true);
        boolean serviceEnabled = prefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, true);
        boolean canWriteSettings = Settings.System.canWrite(context);

        if (!shouldStartService(startOnBoot, serviceEnabled, canWriteSettings)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Not starting service. startOnBoot=" + startOnBoot
                        + " serviceEnabled=" + serviceEnabled
                        + " canWrite=" + canWriteSettings);
            }
            return;
        }

        Intent serviceIntent = new Intent(context, RotationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    /**
     * Pure decision used by onReceive. The service is started only when all
     * three gates open: the user has opted into start-on-boot, the service
     * is enabled, and the WRITE_SETTINGS permission is still granted.
     */
    @VisibleForTesting
    static boolean shouldStartService(boolean startOnBoot, boolean serviceEnabled,
                                       boolean canWriteSettings) {
        return startOnBoot && serviceEnabled && canWriteSettings;
    }
}
