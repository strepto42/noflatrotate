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
import android.content.pm.ServiceInfo;
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

import androidx.annotation.StringRes;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Monitors the accelerometer and locks rotation when the device is flat.
 * Threading: all mutable fields below are read/written on the main thread.
 * Sensor callbacks are delivered on the main looper because we don't pass a
 * Handler to registerListener(); the config-change BroadcastReceiver also
 * fires on the main thread. Fields are still marked volatile so the
 * invariant survives a future change (e.g. moving sensor work to a
 * HandlerThread) without subtle visibility bugs.
 */
public class RotationService extends Service implements SensorEventListener {

    private static final String TAG = "RotationService";
    public static final String ACTION_CONFIG_CHANGED = "com.truffulatree.noflatrotate.CONFIG_CHANGED";

    static final String CHANNEL_ID = "RotationServiceChannel";
    static final int NOTIFICATION_ID = 1;
    private static final int SENSOR_DELAY_MICROS = 100 * 1000; // 100ms

    @VisibleForTesting
    static final double MIN_MAGNITUDE = 0.1;

    /**
     * Persisted bookkeeping flag: "did this service (in a possibly earlier
     * process incarnation) put ACCELEROMETER_ROTATION into the locked state?"
     * Survives process death so we can unlock when the device is picked up
     * again after a reboot / OOM kill / app update.
     */
    @VisibleForTesting
    static final String KEY_ROTATION_PREVIOUSLY_LOCKED = "rotation_previously_locked";

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private WindowManager windowManager;

    private volatile float flatThresholdDegrees = MainActivity.DEFAULT_FLAT_THRESHOLD;
    private volatile float verticalThresholdDegrees = MainActivity.DEFAULT_VERTICAL_THRESHOLD;

    private volatile boolean rotationPreviouslyLocked = false;
    private volatile boolean deviceInFlatMode = false;
    private volatile int lastStableRotation = Surface.ROTATION_0;

    private boolean configReceiverRegistered = false;

    // Most-recent state we surfaced via the notification, so we only call
    // NotificationManager.notify() when the displayed text needs to change.
    private boolean lastNotifiedPermissionOk = true;

    // Indirection so tests can simulate "WRITE_SETTINGS revoked" — Robolectric
    // 4.16 has no setCanWrite shadow for Settings.System.
    @VisibleForTesting
    BooleanSupplier canWriteSettingsSupplier = () ->
            Settings.System.canWrite(getApplicationContext());

    private final BroadcastReceiver configChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                loadThresholdsFromPreferences();
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Config changed. flat=" + flatThresholdDegrees
                            + " vertical=" + verticalThresholdDegrees);
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (BuildConfig.DEBUG) Log.d(TAG, "Service onCreate");

        loadThresholdsFromPreferences();
        rotationPreviouslyLocked = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(KEY_ROTATION_PREVIOUSLY_LOCKED, false);

        IntentFilter filter = new IntentFilter(ACTION_CONFIG_CHANGED);
        ContextCompat.registerReceiver(this, configChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        configReceiverRegistered = true;

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (sensorManager == null) {
            Log.e(TAG, "SensorManager not available. Stopping service.");
            stopSelf();
            return;
        }
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accelerometer == null) {
            Log.e(TAG, "Accelerometer not available. Stopping service.");
            stopSelf();
            return;
        }
        lastStableRotation = getCurrentRotation();
        createNotificationChannel();
    }

    private int getCurrentRotation() {
        // getDefaultDisplay() is deprecated but getDisplay() requires a visual
        // context (Activity), which a Service is not. This is the correct
        // call site for a Service.
        return windowManager.getDefaultDisplay().getRotation();
    }

    private void loadThresholdsFromPreferences() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        flatThresholdDegrees = prefs.getInt(MainActivity.KEY_FLAT_THRESHOLD, MainActivity.DEFAULT_FLAT_THRESHOLD);
        verticalThresholdDegrees = prefs.getInt(MainActivity.KEY_VERTICAL_THRESHOLD, MainActivity.DEFAULT_VERTICAL_THRESHOLD);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Service onStartCommand");
        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer not available when starting command. Service will stop.");
            stopSelf();
            return START_NOT_STICKY;
        }

        boolean permissionOk = canWriteSettingsSupplier.getAsBoolean();
        Notification notification = buildNotification(this, permissionOk);
        lastNotifiedPermissionOk = permissionOk;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        sensorManager.registerListener(this, accelerometer, SENSOR_DELAY_MICROS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (BuildConfig.DEBUG) Log.d(TAG, "Service onDestroy");

        if (configReceiverRegistered) {
            unregisterReceiver(configChangedReceiver);
            configReceiverRegistered = false;
        }

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (rotationPreviouslyLocked) {
            // Best-effort unlock at shutdown. If this fails (e.g. permission
            // revoked) the persisted flag stays true so a future service
            // instance can finish the job on its next non-flat sample.
            if (unlockRotation() && BuildConfig.DEBUG) {
                Log.d(TAG, "Service destroyed → re-enabled auto-rotation.");
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        double angle = evaluateRawSample(event.values[0], event.values[1], event.values[2]);
        if (angle < 0) {
            Log.w(TAG, "Sensor magnitude too small; skipping sample.");
            return;
        }
        handleRotationState(deviceInFlatMode, angle);
    }

    /**
     * Pure-ish decision step: maps one raw accelerometer sample to the new
     * deviceInFlatMode value and returns the computed angle (or -1 if the
     * sample is unusable). No system writes, no Context required — so tests
     * can drive this directly without a Robolectric service shell.
     * No smoothing or debounce: the app exists to lock the *instant* the
     * device is laid flat. Hysteresis (different flat/unlock thresholds) is
     * the sole jitter defence.
     */
    @VisibleForTesting
    double evaluateRawSample(float x, float y, float z) {
        double angle = computeAngleFromVertical(x, y, z);
        if (angle < 0) return angle;
        deviceInFlatMode = shouldBeFlat(angle, deviceInFlatMode,
                flatThresholdDegrees, verticalThresholdDegrees);
        return angle;
    }

    @VisibleForTesting
    boolean isDeviceInFlatModeForTesting() {
        return deviceInFlatMode;
    }

    @VisibleForTesting
    boolean isRotationPreviouslyLockedForTesting() {
        return rotationPreviouslyLocked;
    }

    @VisibleForTesting
    void handleRotationStateForTesting(boolean isFlat, double angle) {
        handleRotationState(isFlat, angle);
    }

    /**
     * Returns the angle (in degrees) between the device's z-axis and the
     * gravity vector, treating face-up and face-down both as 0°.
     * Returns -1 if the sensor sample is too small to be trustworthy.
     */
    @VisibleForTesting
    static double computeAngleFromVertical(float x, float y, float z) {
        double magnitude = Math.sqrt((double) x * x + (double) y * y + (double) z * z);
        if (magnitude < MIN_MAGNITUDE) return -1.0;
        double normalizedZ = Math.abs(z) / magnitude;
        if (normalizedZ > 1.0) normalizedZ = 1.0; // guard against FP rounding past 1
        return Math.acos(normalizedZ) * 180.0 / Math.PI;
    }

    /**
     * Hysteresis: once flat, only the (larger) unlock threshold can break us
     * out; otherwise the (smaller) flat threshold gates entry. acos returns
     * [0, π] so angleFromVertical is always non-negative — no abs() needed.
     */
    @VisibleForTesting
    static boolean shouldBeFlat(double angleFromVertical, boolean currentlyFlat,
                                 float flatThreshold, float verticalThreshold) {
        float threshold = currentlyFlat ? verticalThreshold : flatThreshold;
        return angleFromVertical < threshold
                || (180.0 - angleFromVertical) < threshold;
    }

    @VisibleForTesting
    @StringRes
    static int notificationTextResource(boolean permissionOk) {
        return permissionOk
                ? R.string.service_notification_text
                : R.string.service_no_permission_text;
    }

    private void handleRotationState(boolean isFlat, double angle) {
        try {
            int currentRotationSetting = Settings.System.getInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1);
            boolean rotationEnabled = currentRotationSetting == 1;

            if (!isFlat) {
                lastStableRotation = getCurrentRotation();
            }

            if (isFlat) {
                if (rotationEnabled) {
                    if (lockRotationToStable() && BuildConfig.DEBUG) {
                        Log.d(TAG, "Flat → lock rotation=" + lastStableRotation
                                + " angle=" + String.format(Locale.ROOT, "%.1f", angle));
                    }
                }
            } else {
                if (!rotationEnabled && rotationPreviouslyLocked) {
                    if (unlockRotation() && BuildConfig.DEBUG) {
                        Log.d(TAG, "Not flat → unlock angle="
                                + String.format(Locale.ROOT, "%.1f", angle));
                    }
                } else if (rotationEnabled && rotationPreviouslyLocked) {
                    // User re-enabled rotation externally while we thought we
                    // were holding the lock — clear our bookkeeping.
                    setRotationPreviouslyLocked(false);
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Not flat → user re-enabled externally; clearing lock flag");
                    }
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while accessing settings: " + e.getMessage());
        }
    }

    /**
     * Mutates the rotationPreviouslyLocked field AND mirrors it to
     * SharedPreferences so the bookkeeping survives process death. All
     * lock-state changes must go through this method.
     */
    private void setRotationPreviouslyLocked(boolean locked) {
        rotationPreviouslyLocked = locked;
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ROTATION_PREVIOUSLY_LOCKED, locked)
                .apply();
    }

    /** Returns true on successful system write. Only on success do we record
     *  having locked — otherwise we'd lie about persistent state. */
    private boolean lockRotationToStable() {
        if (!canWriteSettingsSupplier.getAsBoolean()) {
            updateNotificationForPermissionState(false);
            return false;
        }
        try {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.USER_ROTATION, lastStableRotation);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            setRotationPreviouslyLocked(true);
            updateNotificationForPermissionState(true);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Error locking screen rotation: " + e.getMessage());
            updateNotificationForPermissionState(false);
            return false;
        }
    }

    /** Returns true on successful system write. If unlock fails (permission
     *  revoked, etc.) the flag stays true so the next sample can retry. */
    private boolean unlockRotation() {
        if (!canWriteSettingsSupplier.getAsBoolean()) {
            updateNotificationForPermissionState(false);
            return false;
        }
        try {
            Settings.System.putInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1);
            setRotationPreviouslyLocked(false);
            updateNotificationForPermissionState(true);
            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Error unlocking screen rotation: " + e.getMessage());
            updateNotificationForPermissionState(false);
            return false;
        }
    }

    private void updateNotificationForPermissionState(boolean permissionOk) {
        if (permissionOk == lastNotifiedPermissionOk) return;
        lastNotifiedPermissionOk = permissionOk;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(this, permissionOk));
        }
    }

    private static Notification buildNotification(Context context, boolean permissionOk) {
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.service_notification_title))
                .setContentText(context.getString(notificationTextResource(permissionOk)))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Sensor accuracy changed for " + sensor.getName() + ": " + accuracy);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        } else {
            Log.e(TAG, "NotificationManager not available for creating channel.");
        }
    }
}
