package com.truffulatree.noflatrotate;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Surface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.N, Build.VERSION_CODES.P, Build.VERSION_CODES.TIRAMISU})
public class RotationServiceTest {

    private Context context;
    private RotationService service;

    private static final float FLAT = MainActivity.DEFAULT_FLAT_THRESHOLD;
    private static final float UNLOCK = MainActivity.DEFAULT_VERTICAL_THRESHOLD;
    private static final float GRAVITY = 9.81f;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        service = new RotationService();
        context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply();
    }

    /** Builds a fully-initialised service inside Robolectric. Adds the
     * accelerometer shadow first so onCreate doesn't stopSelf, and grants the
     * DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION that ContextCompat requires
     * (the production AAR manifest merger adds it; tests have to grant it). */
    private RotationService buildLiveService() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                context.getPackageName() + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
        SensorManager sm = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor accel = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);
        shadowOf(sm).addSensor(accel);
        ServiceController<RotationService> controller =
                Robolectric.buildService(RotationService.class).create();
        return controller.get();
    }

    // ==================== Basic Service Tests ====================

    @Test
    public void serviceConstantsAreCorrect() {
        assertEquals("RotationServiceChannel", RotationService.CHANNEL_ID);
        assertEquals(1, RotationService.NOTIFICATION_ID);
    }

    @Test
    public void serviceIntent_canBeCreated() {
        Intent serviceIntent = new Intent(context, RotationService.class);
        assertNotNull(serviceIntent.getComponent());
        assertEquals(RotationService.class.getName(), serviceIntent.getComponent().getClassName());
    }

    @Test
    public void sensorManager_isAvailable() {
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        assertNotNull(sensorManager);

        ShadowSensorManager shadowSensorManager = shadowOf(sensorManager);
        Sensor accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);
        shadowSensorManager.addSensor(accelerometer);

        assertNotNull(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
    }

    @Test
    public void onBind_returnsNull() {
        assertNull(service.onBind(new Intent()));
    }

    // ==================== computeAngleFromVertical (production method) ====================

    @Test
    public void computeAngleFromVertical_flatFaceUp_returnsZero() {
        assertEquals(0.0, RotationService.computeAngleFromVertical(0f, 0f, GRAVITY), 1.0);
    }

    @Test
    public void computeAngleFromVertical_flatFaceDown_returnsZero() {
        // abs(z) means face-down also reads as 0 degrees from horizontal
        assertEquals(0.0, RotationService.computeAngleFromVertical(0f, 0f, -GRAVITY), 1.0);
    }

    @Test
    public void computeAngleFromVertical_portraitVertical_returnsNinety() {
        assertEquals(90.0, RotationService.computeAngleFromVertical(0f, GRAVITY, 0f), 1.0);
    }

    @Test
    public void computeAngleFromVertical_landscapeVertical_returnsNinety() {
        assertEquals(90.0, RotationService.computeAngleFromVertical(GRAVITY, 0f, 0f), 1.0);
    }

    @Test
    public void computeAngleFromVertical_fortyFiveDegrees() {
        float c = (float) (GRAVITY / Math.sqrt(2));
        assertEquals(45.0, RotationService.computeAngleFromVertical(0f, c, c), 1.0);
    }

    @Test
    public void computeAngleFromVertical_atFlatThreshold() {
        double radians = Math.toRadians(FLAT);
        float z = (float) (GRAVITY * Math.cos(radians));
        float y = (float) (GRAVITY * Math.sin(radians));
        assertEquals(FLAT, RotationService.computeAngleFromVertical(0f, y, z), 1.0);
    }

    @Test
    public void computeAngleFromVertical_atUnlockThreshold() {
        double radians = Math.toRadians(UNLOCK);
        float z = (float) (GRAVITY * Math.cos(radians));
        float y = (float) (GRAVITY * Math.sin(radians));
        assertEquals(UNLOCK, RotationService.computeAngleFromVertical(0f, y, z), 1.0);
    }

    @Test
    public void computeAngleFromVertical_verySmallMagnitude_returnsInvalid() {
        assertEquals(-1.0, RotationService.computeAngleFromVertical(0.01f, 0.01f, 0.01f), 0.0);
    }

    @Test
    public void computeAngleFromVertical_normalMagnitude_returnsValid() {
        assertTrue(RotationService.computeAngleFromVertical(0f, 0f, GRAVITY) >= 0);
    }

    // ==================== shouldBeFlat (production method) ====================

    @Test
    public void shouldBeFlat_entersFlatJustUnderThreshold() {
        assertTrue(RotationService.shouldBeFlat(FLAT - 1.0, false, FLAT, UNLOCK));
    }

    @Test
    public void shouldBeFlat_doesNotEnterJustOverThreshold() {
        assertFalse(RotationService.shouldBeFlat(FLAT + 1.0, false, FLAT, UNLOCK));
    }

    @Test
    public void shouldBeFlat_staysFlatInHysteresisBand() {
        assertTrue(RotationService.shouldBeFlat(25.0, true, FLAT, UNLOCK));
    }

    @Test
    public void shouldBeFlat_exitsAtUnlockThreshold() {
        assertFalse(RotationService.shouldBeFlat(UNLOCK + 1.0, true, FLAT, UNLOCK));
    }

    @Test
    public void shouldBeFlat_faceDownStillFlat() {
        // 180 degrees from vertical = face down = still "flat"
        assertTrue(RotationService.shouldBeFlat(179.0, false, FLAT, UNLOCK));
    }

    @Test
    public void shouldBeFlat_hysteresisPreventsBouncing() {
        // Angles are expressed relative to the configured thresholds so this
        // test stays meaningful if FLAT/UNLOCK change.
        final double wellAboveUnlock = UNLOCK + 20;
        final double wellBelowFlat = FLAT - 5;
        final double midBand = (FLAT + UNLOCK) / 2.0;
        final double justAboveFlat = FLAT + 2;
        final double justAboveUnlock = UNLOCK + 5;

        boolean inFlat = false;
        inFlat = RotationService.shouldBeFlat(wellAboveUnlock, inFlat, FLAT, UNLOCK);
        assertFalse(inFlat);
        inFlat = RotationService.shouldBeFlat(wellBelowFlat, inFlat, FLAT, UNLOCK);
        assertTrue(inFlat);
        inFlat = RotationService.shouldBeFlat(midBand, inFlat, FLAT, UNLOCK);
        assertTrue("Should stay flat inside hysteresis band", inFlat);
        inFlat = RotationService.shouldBeFlat(justAboveFlat, inFlat, FLAT, UNLOCK);
        assertTrue(inFlat);
        inFlat = RotationService.shouldBeFlat(justAboveUnlock, inFlat, FLAT, UNLOCK);
        assertFalse(inFlat);
        inFlat = RotationService.shouldBeFlat(midBand, inFlat, FLAT, UNLOCK);
        assertFalse("Should not re-enter flat in mid-band — need < FLAT", inFlat);
    }

    @Test
    public void shouldBeFlat_configurableTighterThresholds() {
        float flat = 10f, unlock = 15f;
        assertFalse(RotationService.shouldBeFlat(12.0, false, flat, unlock));
        assertTrue(RotationService.shouldBeFlat(8.0, false, flat, unlock));
        assertTrue(RotationService.shouldBeFlat(12.0, true, flat, unlock));
        assertFalse(RotationService.shouldBeFlat(16.0, true, flat, unlock));
    }

    @Test
    public void shouldBeFlat_configurableLooserThresholds() {
        float flat = 35f, unlock = 45f;
        assertTrue(RotationService.shouldBeFlat(30.0, false, flat, unlock));
        assertFalse(RotationService.shouldBeFlat(40.0, false, flat, unlock));
        assertTrue(RotationService.shouldBeFlat(40.0, true, flat, unlock));
    }

    // ==================== Responsiveness: raw samples, no smoothing lag ====================

    /**
     * The whole point of the app is to lock rotation the moment the device goes
     * flat. A single sample below the flat threshold must immediately flip
     * deviceInFlatMode. Any smoothing or debounce that delays this would let
     * the OS rotate during the put-down gesture.
     */
    @Test
    public void evaluateRawSample_singleFlatSampleAfterVertical_immediatelyFlat() {
        // Several samples at vertical to establish "not flat"
        for (int i = 0; i < 5; i++) {
            service.evaluateRawSample(GRAVITY, 0f, 0f);
        }
        assertFalse("Should not be flat while vertical", service.isDeviceInFlatModeForTesting());

        // One sample at flat — must immediately trigger flat mode
        service.evaluateRawSample(0f, 0f, GRAVITY);
        assertTrue("Single flat sample must immediately trigger flat mode (no smoothing lag)",
                service.isDeviceInFlatModeForTesting());
    }

    @Test
    public void evaluateRawSample_singleVerticalSampleAfterFlat_exitsImmediately() {
        // Several flat samples to enter flat mode
        for (int i = 0; i < 5; i++) {
            service.evaluateRawSample(0f, 0f, GRAVITY);
        }
        assertTrue(service.isDeviceInFlatModeForTesting());

        // One sample at vertical — must immediately exit flat mode
        service.evaluateRawSample(GRAVITY, 0f, 0f);
        assertFalse("Single vertical sample must immediately exit flat mode",
                service.isDeviceInFlatModeForTesting());
    }

    @Test
    public void evaluateRawSample_invalidMagnitude_keepsState() {
        // Establish flat state
        service.evaluateRawSample(0f, 0f, GRAVITY);
        assertTrue(service.isDeviceInFlatModeForTesting());

        // Garbage sample (magnitude < MIN_MAGNITUDE) must not change state
        service.evaluateRawSample(0.01f, 0.01f, 0.01f);
        assertTrue("Invalid sample should leave flat state unchanged",
                service.isDeviceInFlatModeForTesting());
    }

    // ==================== Defaults and constants ====================

    @Test
    public void defaultThresholds_matchExpected() {
        assertEquals(35, MainActivity.DEFAULT_FLAT_THRESHOLD);
        assertEquals(50, MainActivity.DEFAULT_VERTICAL_THRESHOLD);
    }

    @Test
    public void preferenceKeys_areDefined() {
        assertNotNull(MainActivity.PREFS_NAME);
        assertNotNull(MainActivity.KEY_FLAT_THRESHOLD);
        assertNotNull(MainActivity.KEY_VERTICAL_THRESHOLD);
        assertNotNull(MainActivity.KEY_START_ON_BOOT);
    }

    @Test
    public void configChangedAction_isDefined() {
        assertNotNull(RotationService.ACTION_CONFIG_CHANGED);
        assertTrue(RotationService.ACTION_CONFIG_CHANGED.contains("noflatrotate"));
    }

    @Test
    public void rotationConstants_areDistinct() {
        assertEquals(0, Surface.ROTATION_0);
        assertEquals(1, Surface.ROTATION_90);
        assertEquals(2, Surface.ROTATION_180);
        assertEquals(3, Surface.ROTATION_270);
    }

    // ==================== Notification text selection (L17) ====================

    @Test
    public void notificationTextResource_permissionOk_returnsNormalText() {
        assertEquals(R.string.service_notification_text,
                RotationService.notificationTextResource(true));
    }

    @Test
    public void notificationTextResource_permissionMissing_returnsWarning() {
        assertEquals(R.string.service_no_permission_text,
                RotationService.notificationTextResource(false));
    }

    // ==================== Persistence: rotationPreviouslyLocked survives restart ====================
    //
    // The service-process-local "did I lock rotation?" flag must be persisted
    // so that after process death / reboot / app update we still recognise our
    // own lock and can unlock when the device is picked up again.

    @Test
    public void onCreate_readsPersistedRotationLockState() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(RotationService.KEY_ROTATION_PREVIOUSLY_LOCKED, true).apply();

        RotationService live = buildLiveService();

        assertTrue("Service should restore rotation-lock bookkeeping from prefs",
                live.isRotationPreviouslyLockedForTesting());
    }

    @Test
    public void onCreate_defaultsRotationLockStateToFalse_whenPrefsUnset() {
        RotationService live = buildLiveService();
        assertFalse(live.isRotationPreviouslyLockedForTesting());
    }

    @Test
    public void lockPath_persistsTrue_onSuccessfulSystemWrite() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 1);

        RotationService live = buildLiveService();

        // Flat sample → expected to lock rotation and persist the flag
        double angle = live.evaluateRawSample(0f, 0f, GRAVITY);
        live.handleRotationStateForTesting(true, angle);

        assertTrue("In-memory flag should be set after lock",
                live.isRotationPreviouslyLockedForTesting());
        assertTrue("Flag should be persisted to prefs",
                prefs.getBoolean(RotationService.KEY_ROTATION_PREVIOUSLY_LOCKED, false));
        assertEquals("ACCELEROMETER_ROTATION should now be 0",
                0, Settings.System.getInt(context.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, -1));
    }

    @Test
    public void unlockPath_persistsFalse_onSuccessfulSystemWrite() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(RotationService.KEY_ROTATION_PREVIOUSLY_LOCKED, true).apply();
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0); // pretend we locked previously

        RotationService live = buildLiveService();
        // Vertical sample → expected to unlock rotation and persist false
        double angle = live.evaluateRawSample(GRAVITY, 0f, 0f);
        live.handleRotationStateForTesting(false, angle);

        assertFalse("In-memory flag should be cleared after unlock",
                live.isRotationPreviouslyLockedForTesting());
        assertFalse("Persisted flag should be cleared after unlock",
                prefs.getBoolean(RotationService.KEY_ROTATION_PREVIOUSLY_LOCKED, true));
        assertEquals("ACCELEROMETER_ROTATION should now be 1",
                1, Settings.System.getInt(context.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, -1));
    }

    @Test
    public void unlockPath_keepsFlagTrue_whenCanWriteIsFalse() {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(RotationService.KEY_ROTATION_PREVIOUSLY_LOCKED, true).apply();
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0);

        RotationService live = buildLiveService();
        // Simulate "WRITE_SETTINGS was revoked" via the injected supplier.
        live.canWriteSettingsSupplier = () -> false;

        double angle = live.evaluateRawSample(GRAVITY, 0f, 0f);
        live.handleRotationStateForTesting(false, angle);

        // System state should NOT have changed
        assertEquals("ACCELEROMETER_ROTATION should still be 0",
                0, Settings.System.getInt(context.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, -1));
        // Flag should NOT be cleared — we still need to remember we locked it
        assertTrue("Flag must remain true when unlock fails",
                live.isRotationPreviouslyLockedForTesting());
        assertTrue("Persisted flag must remain true when unlock fails",
                prefs.getBoolean(RotationService.KEY_ROTATION_PREVIOUSLY_LOCKED, false));
    }

    @Test
    public void rebootWhileFlat_thenNotFlat_unlocksOnFirstSample() {
        // Repro of the original bug. Simulate the state left over by a prior
        // service instance that locked rotation and then died.
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(RotationService.KEY_ROTATION_PREVIOUSLY_LOCKED, true).apply();
        Settings.System.putInt(context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION, 0);

        // New service instance starts (e.g. after reboot)
        RotationService live = buildLiveService();
        assertTrue("Restored flag from prefs",
                live.isRotationPreviouslyLockedForTesting());

        // User picks the phone up
        double angle = live.evaluateRawSample(GRAVITY, 0f, 0f);
        live.handleRotationStateForTesting(false, angle);

        assertEquals("Rotation must be re-enabled after reboot-while-flat",
                1, Settings.System.getInt(context.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION, -1));
        assertFalse(live.isRotationPreviouslyLockedForTesting());
    }
}
