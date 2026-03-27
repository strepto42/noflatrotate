package com.truffulatree.noflatrotate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class RotationService extends Service implements SensorEventListener {

    private static final String TAG = "RotationService";
    public static final String ACTION_CONFIG_CHANGED = "com.truffulatree.noflatrotate.CONFIG_CHANGED";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    static final String CHANNEL_ID = "RotationServiceChannel";
    static final int NOTIFICATION_ID = 1;
    private static final int SENSOR_DELAY_MICROS = 100 * 1000; // 100ms

    // Configurable thresholds (loaded from preferences)
    private float flatThresholdDegrees = MainActivity.DEFAULT_FLAT_THRESHOLD;
    private float verticalThresholdDegrees = MainActivity.DEFAULT_VERTICAL_THRESHOLD;

    private boolean rotationPreviouslyLocked = false;
    private boolean deviceInFlatMode = false;
    private WindowManager windowManager;
    private int lastStableRotation = Surface.ROTATION_0;

    // Receiver for config changes
    private final BroadcastReceiver configChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                loadThresholdsFromPreferences();
                Log.d(TAG, "Config changed. New thresholds: flat=" + flatThresholdDegrees + ", vertical=" + verticalThresholdDegrees);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");

        // Load thresholds from preferences
        loadThresholdsFromPreferences();

        // Register config change receiver
        IntentFilter filter = new IntentFilter(ACTION_CONFIG_CHANGED);
        ContextCompat.registerReceiver(this, configChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        } else {
            Log.e(TAG, "SensorManager not available. Stopping service.");
            stopSelf();
            return;
        }
        if (accelerometer == null) {
            Log.e(TAG, "Accelerometer not available. Stopping service.");
            stopSelf();
            return;
        }
        // Initialize last stable rotation to current display rotation
        lastStableRotation = getCurrentRotation();
        createNotificationChannel();
    }

    @SuppressWarnings("deprecation")
    private int getCurrentRotation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Display display = getDisplay();
            return display != null ? display.getRotation() : Surface.ROTATION_0;
        } else {
            return windowManager.getDefaultDisplay().getRotation();
        }
    }

    private void loadThresholdsFromPreferences() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        flatThresholdDegrees = prefs.getInt(MainActivity.KEY_FLAT_THRESHOLD, MainActivity.DEFAULT_FLAT_THRESHOLD);
        verticalThresholdDegrees = prefs.getInt(MainActivity.KEY_VERTICAL_THRESHOLD, MainActivity.DEFAULT_VERTICAL_THRESHOLD);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer not available when starting command. Service will stop.");
            stopSelf();
            return START_NOT_STICKY;
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(getString(R.string.service_notification_text))
                .setSmallIcon(R.mipmap.ic_launcher) // TODO: Consider a dedicated monochrome notification icon
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        sensorManager.registerListener(this, accelerometer, SENSOR_DELAY_MICROS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Service onDestroy");

        // Unregister config change receiver
        try {
            unregisterReceiver(configChangedReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Config receiver was not registered");
        }

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (rotationPreviouslyLocked) {
            setAutoOrientationEnabled(true);
            Log.d(TAG, "Service destroyed. Re-enabled auto-rotation.");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];

            // Calculate device orientation angle from vertical
            double magnitude = Math.sqrt(x * x + y * y + z * z);
            if (magnitude < 0.1) { // Avoid division by very small numbers
                Log.w(TAG, "Sensor returned very small magnitude vector: " + magnitude);
                return;
            }

            // Normalize z component and calculate angle from vertical
            double normalizedZ = Math.abs(z) / magnitude;
            normalizedZ = Math.max(0.0, Math.min(1.0, normalizedZ)); // Clamp to valid range
            double angleFromVertical = Math.acos(normalizedZ) * 180.0 / Math.PI;

            // Implement hysteresis for flat detection using configurable thresholds
            boolean shouldBeFlat;
            if (!deviceInFlatMode) {
                // Not currently in flat mode - transition to flat at flatThresholdDegrees
                shouldBeFlat = (Math.abs(angleFromVertical) < flatThresholdDegrees) ||
                               (Math.abs(angleFromVertical - 180.0) < flatThresholdDegrees);
            } else {
                // Currently in flat mode - only exit flat mode at verticalThresholdDegrees
                shouldBeFlat = (Math.abs(angleFromVertical) < verticalThresholdDegrees) ||
                               (Math.abs(angleFromVertical - 180.0) < verticalThresholdDegrees);
            }

            // Update the flat mode state
            deviceInFlatMode = shouldBeFlat;

            handleRotationState(shouldBeFlat, angleFromVertical);
        }
    }

    private void handleRotationState(boolean isFlat, double angle) {
        try {
            int currentRotationSetting = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
            boolean rotationEnabled = currentRotationSetting == 1;

            // Always track the current display rotation when not flat
            // This ensures we remember what orientation the user had before laying the device flat
            if (!isFlat) {
                lastStableRotation = getCurrentRotation();
            }

            if (isFlat) {
                if (rotationEnabled) {
                    lockRotationToStable();
                    rotationPreviouslyLocked = true;
                    Log.d(TAG, "Device is flat. Locking to last stable rotation: " + lastStableRotation + ". Angle from vertical: " + String.format("%.1f", angle));
                }
            } else {
                if (!rotationEnabled && rotationPreviouslyLocked) {
                    unlockRotation();
                    rotationPreviouslyLocked = false;
                    Log.d(TAG, "Device is not flat. Unlocking screen rotation. Angle from vertical: " + String.format("%.1f", angle));
                } else if (rotationEnabled && rotationPreviouslyLocked) {
                    rotationPreviouslyLocked = false;
                    Log.d(TAG, "Device is not flat. Rotation already enabled externally. Resetting lock flag. Angle from vertical: " + String.format("%.1f", angle));
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while accessing settings: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Unexpected error handling rotation state: " + e.getMessage());
        }
    }

    private void lockRotationToStable() {
        try {
            if (Settings.System.canWrite(getApplicationContext())) {
                // Use the last stable rotation (captured when device was not flat)
                // This prevents locking to a transitional orientation
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, lastStableRotation);
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
            } else {
                Log.w(TAG, "Cannot write settings. WRITE_SETTINGS permission not granted.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error locking screen rotation: " + e.getMessage());
        }
    }

    private void unlockRotation() {
        try {
            if (Settings.System.canWrite(getApplicationContext())) {
                // Simply re-enable auto-rotation without changing USER_ROTATION
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
            } else {
                Log.w(TAG, "Cannot write settings. WRITE_SETTINGS permission not granted.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unlocking screen rotation: " + e.getMessage());
        }
    }

    private void setAutoOrientationEnabled(boolean enabled) {
        try {
            if (Settings.System.canWrite(getApplicationContext())) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, enabled ? 1 : 0);
            } else {
                Log.w(TAG, "Cannot write settings. WRITE_SETTINGS permission not granted.");
            }
        } catch (Exception e) { // Catching a broad exception as putInt can also throw SecurityException
            Log.e(TAG, "Error changing screen rotation setting: " + e.getMessage());
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d(TAG, "Sensor accuracy changed for " + sensor.getName() + ": " + accuracy);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription(getString(R.string.notification_channel_description));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            } else {
                Log.e(TAG, "NotificationManager not available for creating channel.");
            }
        }
    }
}
