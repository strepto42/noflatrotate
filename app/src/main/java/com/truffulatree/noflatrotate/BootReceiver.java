package com.truffulatree.noflatrotate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Receives the BOOT_COMPLETED broadcast and starts the RotationService
 * if the user has enabled start on boot.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "Boot completed received");
            
            // Check if start on boot is enabled
            SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
            boolean startOnBoot = prefs.getBoolean(MainActivity.KEY_START_ON_BOOT, true);
            
            if (startOnBoot) {
                Log.d(TAG, "Starting RotationService after boot");
                Intent serviceIntent = new Intent(context, RotationService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.d(TAG, "Start on boot is disabled, not starting service");
            }
        }
    }
}

