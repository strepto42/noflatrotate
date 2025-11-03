package com.truffulatree.noflatrotate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

public class RotationService extends Service implements SensorEventListener {

    private static final String TAG = "RotationService";
    private SensorManager sensorManager;
    private Sensor accelerometer;
    static final String CHANNEL_ID = "RotationServiceChannel";
    static final int NOTIFICATION_ID = 1;
    private static final int SENSOR_DELAY_MICROS = 100 * 1000; // 100ms
    private static final float FLAT_THRESHOLD_DEGREES = 20.0f; // Threshold for disabling rotation
    private static final float VERTICAL_THRESHOLD_DEGREES = 25.0f; // Threshold for re-enabling rotation (hysteresis)
    private boolean rotationPreviouslyLocked = false;
    private boolean deviceInFlatMode = false; // Track current flat state for hysteresis
    private WindowManager windowManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
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
        createNotificationChannel();
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

            // Implement hysteresis for flat detection
            boolean shouldBeFlat;
            if (!deviceInFlatMode) {
                // Not currently in flat mode - transition to flat at 20 degrees
                shouldBeFlat = (Math.abs(angleFromVertical) < FLAT_THRESHOLD_DEGREES) ||
                               (Math.abs(angleFromVertical - 180.0) < FLAT_THRESHOLD_DEGREES);
            } else {
                // Currently in flat mode - only exit flat mode at 25 degrees
                shouldBeFlat = (Math.abs(angleFromVertical) < VERTICAL_THRESHOLD_DEGREES) ||
                               (Math.abs(angleFromVertical - 180.0) < VERTICAL_THRESHOLD_DEGREES);
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

            if (isFlat) {
                if (rotationEnabled) {
                    lockRotationToCurrent();
                    rotationPreviouslyLocked = true;
                    Log.d(TAG, "Device is flat. Locking current screen rotation. Angle from vertical: " + String.format("%.1f", angle));
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

    private void lockRotationToCurrent() {
        try {
            if (Settings.System.canWrite(getApplicationContext())) {
                // Get the current screen rotation
                int currentRotation = windowManager.getDefaultDisplay().getRotation();

                // Map the rotation to Settings.System.USER_ROTATION values
                int userRotation;
                switch (currentRotation) {
                    case Surface.ROTATION_0:
                        userRotation = Surface.ROTATION_0;
                        break;
                    case Surface.ROTATION_90:
                        userRotation = Surface.ROTATION_90;
                        break;
                    case Surface.ROTATION_180:
                        userRotation = Surface.ROTATION_180;
                        break;
                    case Surface.ROTATION_270:
                        userRotation = Surface.ROTATION_270;
                        break;
                    default:
                        userRotation = Surface.ROTATION_0;
                }

                // Lock rotation to current orientation
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, userRotation);
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
