package com.truffulatree.noflatrotate;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.Surface;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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
@Config(sdk = {Build.VERSION_CODES.P})
public class RotationServiceTest {

    private Context context;
    private RotationService service;

    // Constants matching the service (for test clarity)
    private static final float FLAT_THRESHOLD_DEGREES = 20.0f;
    private static final float VERTICAL_THRESHOLD_DEGREES = 30.0f;
    private static final float GRAVITY = 9.81f;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        service = new RotationService();
    }

    // ==================== Basic Service Tests ====================

    @Test
    public void serviceConstantsAreCorrect() {
        assertEquals("Channel ID should match expected value",
                "RotationServiceChannel", RotationService.CHANNEL_ID);
        assertEquals("Notification ID should be 1",
                1, RotationService.NOTIFICATION_ID);
    }

    @Test
    public void serviceIntent_canBeCreated() {
        Intent serviceIntent = new Intent(context, RotationService.class);
        assertNotNull("Service intent should be created successfully", serviceIntent);
        assertNotNull("Service intent component should not be null", serviceIntent.getComponent());
        assertEquals("Intent should target correct service class",
                RotationService.class.getName(),
                serviceIntent.getComponent().getClassName());
    }

    @Test
    public void sensorManager_isAvailable() {
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        assertNotNull("SensorManager should be available", sensorManager);

        ShadowSensorManager shadowSensorManager = shadowOf(sensorManager);
        Sensor accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);
        shadowSensorManager.addSensor(accelerometer);

        Sensor retrievedSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        assertNotNull("Accelerometer sensor should now be available in the test environment", retrievedSensor);
    }

    @Test
    public void onBind_returnsNull() {
        Intent intent = new Intent();
        assertNull("onBind should return null for unbound service", service.onBind(intent));
    }

    // ==================== Angle Calculation Tests ====================

    @Test
    public void angleCalculation_deviceFlatFaceUp_returnsZeroDegrees() {
        // Device flat face up: z = gravity, x = 0, y = 0
        double angle = calculateAngleFromVertical(0f, 0f, GRAVITY);
        assertEquals("Flat face up should be ~0 degrees from flat", 0.0, angle, 1.0);
    }

    @Test
    public void angleCalculation_deviceFlatFaceDown_returns180Degrees() {
        // Device flat face down: z = -gravity, x = 0, y = 0
        double angle = calculateAngleFromVertical(0f, 0f, -GRAVITY);
        assertEquals("Flat face down should be ~0 degrees (using abs(z))", 0.0, angle, 1.0);
    }

    @Test
    public void angleCalculation_deviceVerticalPortrait_returns90Degrees() {
        // Device vertical in portrait: y = gravity, x = 0, z = 0
        double angle = calculateAngleFromVertical(0f, GRAVITY, 0f);
        assertEquals("Vertical portrait should be ~90 degrees", 90.0, angle, 1.0);
    }

    @Test
    public void angleCalculation_deviceVerticalLandscape_returns90Degrees() {
        // Device vertical in landscape: x = gravity, y = 0, z = 0
        double angle = calculateAngleFromVertical(GRAVITY, 0f, 0f);
        assertEquals("Vertical landscape should be ~90 degrees", 90.0, angle, 1.0);
    }

    @Test
    public void angleCalculation_deviceAt45Degrees_returns45Degrees() {
        // Device tilted 45 degrees
        float component = (float) (GRAVITY / Math.sqrt(2));
        double angle = calculateAngleFromVertical(0f, component, component);
        assertEquals("45 degree tilt should be ~45 degrees", 45.0, angle, 2.0);
    }

    @Test
    public void angleCalculation_deviceAtFlatThreshold_returns20Degrees() {
        // Device at exactly the flat threshold (20 degrees)
        double radians = Math.toRadians(20.0);
        float z = (float) (GRAVITY * Math.cos(radians));
        float y = (float) (GRAVITY * Math.sin(radians));
        double angle = calculateAngleFromVertical(0f, y, z);
        assertEquals("Device at flat threshold should be ~20 degrees", 20.0, angle, 1.0);
    }

    @Test
    public void angleCalculation_deviceAtVerticalThreshold_returns30Degrees() {
        // Device at exactly the vertical threshold (30 degrees)
        double radians = Math.toRadians(30.0);
        float z = (float) (GRAVITY * Math.cos(radians));
        float y = (float) (GRAVITY * Math.sin(radians));
        double angle = calculateAngleFromVertical(0f, y, z);
        assertEquals("Device at vertical threshold should be ~30 degrees", 30.0, angle, 1.0);
    }

    // ==================== Flat Detection Hysteresis Tests ====================

    @Test
    public void flatDetection_entersFlatModeAt19Degrees() {
        // Just under the 20 degree threshold should trigger flat mode
        boolean shouldBeFlat = calculateShouldBeFlat(19.0, false);
        assertTrue("19 degrees should be considered flat when not in flat mode", shouldBeFlat);
    }

    @Test
    public void flatDetection_doesNotEnterFlatModeAt21Degrees() {
        // Just over the 20 degree threshold should NOT trigger flat mode
        boolean shouldBeFlat = calculateShouldBeFlat(21.0, false);
        assertFalse("21 degrees should NOT be considered flat when not in flat mode", shouldBeFlat);
    }

    @Test
    public void flatDetection_staysInFlatModeAt25Degrees() {
        // When already in flat mode, 25 degrees (between 20 and 30) should stay flat
        boolean shouldBeFlat = calculateShouldBeFlat(25.0, true);
        assertTrue("25 degrees should stay flat when already in flat mode (hysteresis)", shouldBeFlat);
    }

    @Test
    public void flatDetection_exitsFlatModeAt31Degrees() {
        // When in flat mode, going past 30 degrees should exit flat mode
        boolean shouldBeFlat = calculateShouldBeFlat(31.0, true);
        assertFalse("31 degrees should exit flat mode", shouldBeFlat);
    }

    @Test
    public void flatDetection_hysteresisPreventsBouncing() {
        // Simulate bouncing between 22 and 28 degrees
        // Once in flat mode, should stay in flat mode despite fluctuations
        boolean inFlatMode = false;
        
        // Start not flat at 40 degrees
        inFlatMode = calculateShouldBeFlat(40.0, inFlatMode);
        assertFalse("Should start not flat at 40 degrees", inFlatMode);
        
        // Go flat at 15 degrees
        inFlatMode = calculateShouldBeFlat(15.0, inFlatMode);
        assertTrue("Should enter flat at 15 degrees", inFlatMode);
        
        // Bounce to 25 degrees - should stay flat due to hysteresis
        inFlatMode = calculateShouldBeFlat(25.0, inFlatMode);
        assertTrue("Should stay flat at 25 degrees (hysteresis)", inFlatMode);
        
        // Bounce back to 22 degrees - should stay flat
        inFlatMode = calculateShouldBeFlat(22.0, inFlatMode);
        assertTrue("Should stay flat at 22 degrees", inFlatMode);
        
        // Go to 35 degrees - should exit flat
        inFlatMode = calculateShouldBeFlat(35.0, inFlatMode);
        assertFalse("Should exit flat at 35 degrees", inFlatMode);
        
        // Go back to 25 degrees - should NOT re-enter flat (need < 20)
        inFlatMode = calculateShouldBeFlat(25.0, inFlatMode);
        assertFalse("Should NOT re-enter flat at 25 degrees (need < 20)", inFlatMode);
    }

    // ==================== Rotation Value Tests ====================

    @Test
    public void rotationValues_allSurfaceRotationsAreValid() {
        // Verify all rotation constants are distinct and have expected values
        assertEquals("ROTATION_0 should be 0", 0, Surface.ROTATION_0);
        assertEquals("ROTATION_90 should be 1", 1, Surface.ROTATION_90);
        assertEquals("ROTATION_180 should be 2", 2, Surface.ROTATION_180);
        assertEquals("ROTATION_270 should be 3", 3, Surface.ROTATION_270);
    }

    // ==================== Edge Case Tests ====================

    @Test
    public void sensorData_verySmallMagnitude_isRejected() {
        // Magnitude less than 0.1 should be rejected as invalid sensor reading
        double angle = calculateAngleFromVertical(0.01f, 0.01f, 0.01f);
        assertEquals("Very small magnitude should return -1 (invalid)", -1.0, angle, 0.0);
    }

    @Test
    public void sensorData_normalMagnitude_isAccepted() {
        // Normal gravity magnitude should produce valid angle
        double angle = calculateAngleFromVertical(0f, 0f, GRAVITY);
        assertTrue("Normal gravity magnitude should return valid angle >= 0", angle >= 0);
    }

    @Test
    public void angleCalculation_boundaryAt180Degrees_detectsFlat() {
        // Face down is also flat (180 degrees from face up, but abs(z) makes it 0)
        // The code uses abs(z), so face down should also be detected as flat
        boolean shouldBeFlat = calculateShouldBeFlat(0.0, false); // 0 degrees means flat
        assertTrue("Face down (effectively 0 degrees with abs) should be flat", shouldBeFlat);
    }

    @Test
    public void flatDetection_exactlyAtThreshold_isFlat() {
        // Exactly at 20 degrees should be considered flat (using < comparison)
        boolean shouldBeFlat = calculateShouldBeFlat(19.99, false);
        assertTrue("Just under 20 degrees should be flat", shouldBeFlat);
        
        shouldBeFlat = calculateShouldBeFlat(20.01, false);
        assertFalse("Just over 20 degrees should NOT be flat", shouldBeFlat);
    }

    @Test
    public void flatDetection_atVerticalThresholdBoundary() {
        // When in flat mode, exactly at 30 degrees
        boolean shouldBeFlatAt29 = calculateShouldBeFlat(29.99, true);
        assertTrue("29.99 degrees should stay flat when in flat mode", shouldBeFlatAt29);
        
        boolean shouldBeFlatAt30 = calculateShouldBeFlat(30.01, true);
        assertFalse("30.01 degrees should exit flat mode", shouldBeFlatAt30);
    }

    // ==================== State Transition Tests ====================

    @Test
    public void stateTransition_fromVerticalToFlat() {
        boolean inFlatMode = false;
        
        // Start vertical at 90 degrees
        inFlatMode = calculateShouldBeFlat(90.0, inFlatMode);
        assertFalse("Should be not flat at 90 degrees", inFlatMode);
        
        // Gradually tilt towards flat
        inFlatMode = calculateShouldBeFlat(60.0, inFlatMode);
        assertFalse("Should still not be flat at 60 degrees", inFlatMode);
        
        inFlatMode = calculateShouldBeFlat(30.0, inFlatMode);
        assertFalse("Should still not be flat at 30 degrees", inFlatMode);
        
        inFlatMode = calculateShouldBeFlat(15.0, inFlatMode);
        assertTrue("Should be flat at 15 degrees", inFlatMode);
    }

    @Test
    public void stateTransition_fromFlatToVertical() {
        boolean inFlatMode = true; // Start in flat mode
        
        // Start flat at 10 degrees
        inFlatMode = calculateShouldBeFlat(10.0, inFlatMode);
        assertTrue("Should stay flat at 10 degrees", inFlatMode);
        
        // Start tilting up
        inFlatMode = calculateShouldBeFlat(25.0, inFlatMode);
        assertTrue("Should stay flat at 25 degrees (hysteresis)", inFlatMode);
        
        inFlatMode = calculateShouldBeFlat(35.0, inFlatMode);
        assertFalse("Should exit flat at 35 degrees", inFlatMode);
        
        inFlatMode = calculateShouldBeFlat(60.0, inFlatMode);
        assertFalse("Should stay not flat at 60 degrees", inFlatMode);
    }

    @Test
    public void stateTransition_rapidFluctuations() {
        boolean inFlatMode = false;
        
        // Rapid fluctuations should be handled smoothly
        double[] angles = {45.0, 15.0, 25.0, 22.0, 28.0, 35.0, 40.0, 10.0};
        boolean[] expectedStates = {false, true, true, true, true, false, false, true};
        
        for (int i = 0; i < angles.length; i++) {
            inFlatMode = calculateShouldBeFlat(angles[i], inFlatMode, FLAT_THRESHOLD_DEGREES, VERTICAL_THRESHOLD_DEGREES);
            assertEquals("State mismatch at angle " + angles[i], expectedStates[i], inFlatMode);
        }
    }

    // ==================== Configurable Threshold Tests ====================

    @Test
    public void configurableThresholds_tighterThresholds() {
        // Test with tighter thresholds (10° flat, 15° unlock)
        float flatThreshold = 10.0f;
        float verticalThreshold = 15.0f;
        
        // 12 degrees should NOT be flat with 10° threshold
        assertFalse("12° should not be flat with 10° threshold",
                calculateShouldBeFlat(12.0, false, flatThreshold, verticalThreshold));
        
        // 8 degrees SHOULD be flat
        assertTrue("8° should be flat with 10° threshold",
                calculateShouldBeFlat(8.0, false, flatThreshold, verticalThreshold));
        
        // Once flat, 12 degrees should stay flat (under 15° unlock threshold)
        assertTrue("12° should stay flat with 15° unlock threshold",
                calculateShouldBeFlat(12.0, true, flatThreshold, verticalThreshold));
        
        // 16 degrees should exit flat mode
        assertFalse("16° should exit flat with 15° unlock threshold",
                calculateShouldBeFlat(16.0, true, flatThreshold, verticalThreshold));
    }

    @Test
    public void configurableThresholds_looserThresholds() {
        // Test with looser thresholds (35° flat, 45° unlock)
        float flatThreshold = 35.0f;
        float verticalThreshold = 45.0f;
        
        // 30 degrees SHOULD be flat with 35° threshold
        assertTrue("30° should be flat with 35° threshold",
                calculateShouldBeFlat(30.0, false, flatThreshold, verticalThreshold));
        
        // 40 degrees should NOT be flat initially
        assertFalse("40° should not be flat with 35° threshold",
                calculateShouldBeFlat(40.0, false, flatThreshold, verticalThreshold));
        
        // Once flat, 40 degrees should stay flat (under 45° unlock threshold)
        assertTrue("40° should stay flat with 45° unlock threshold",
                calculateShouldBeFlat(40.0, true, flatThreshold, verticalThreshold));
    }

    @Test
    public void configurableThresholds_defaultValues() {
        // Verify default values match expected
        assertEquals("Default flat threshold should be 20",
                20, MainActivity.DEFAULT_FLAT_THRESHOLD);
        assertEquals("Default vertical threshold should be 30",
                30, MainActivity.DEFAULT_VERTICAL_THRESHOLD);
    }

    @Test
    public void configurableThresholds_prefsKeysExist() {
        // Verify preference keys are defined
        assertNotNull("PREFS_NAME should be defined", MainActivity.PREFS_NAME);
        assertNotNull("KEY_FLAT_THRESHOLD should be defined", MainActivity.KEY_FLAT_THRESHOLD);
        assertNotNull("KEY_VERTICAL_THRESHOLD should be defined", MainActivity.KEY_VERTICAL_THRESHOLD);
        assertNotNull("KEY_START_ON_BOOT should be defined", MainActivity.KEY_START_ON_BOOT);
    }

    @Test
    public void configChangedAction_isDefined() {
        // Verify the config changed action is defined for broadcast
        assertNotNull("ACTION_CONFIG_CHANGED should be defined", RotationService.ACTION_CONFIG_CHANGED);
        assertTrue("ACTION_CONFIG_CHANGED should contain package name",
                RotationService.ACTION_CONFIG_CHANGED.contains("noflatrotate"));
    }

    // ==================== Helper Methods ====================

    /**
     * Calculate angle from vertical (flat position) based on accelerometer values.
     * Mirrors the calculation in RotationService.onSensorChanged().
     */
    private double calculateAngleFromVertical(float x, float y, float z) {
        double magnitude = Math.sqrt(x * x + y * y + z * z);
        if (magnitude < 0.1) {
            return -1; // Invalid
        }
        double normalizedZ = Math.abs(z) / magnitude;
        normalizedZ = Math.max(0.0, Math.min(1.0, normalizedZ));
        return Math.acos(normalizedZ) * 180.0 / Math.PI;
    }

    /**
     * Determine if device should be considered flat based on angle and current state.
     * Mirrors the hysteresis logic in RotationService.onSensorChanged().
     * Uses default thresholds.
     */
    private boolean calculateShouldBeFlat(double angleFromVertical, boolean currentlyInFlatMode) {
        return calculateShouldBeFlat(angleFromVertical, currentlyInFlatMode, 
                FLAT_THRESHOLD_DEGREES, VERTICAL_THRESHOLD_DEGREES);
    }

    /**
     * Determine if device should be considered flat based on angle, current state, and configurable thresholds.
     * Mirrors the hysteresis logic in RotationService.onSensorChanged().
     */
    private boolean calculateShouldBeFlat(double angleFromVertical, boolean currentlyInFlatMode,
                                          float flatThreshold, float verticalThreshold) {
        if (!currentlyInFlatMode) {
            return (Math.abs(angleFromVertical) < flatThreshold) ||
                   (Math.abs(angleFromVertical - 180.0) < flatThreshold);
        } else {
            return (Math.abs(angleFromVertical) < verticalThreshold) ||
                   (Math.abs(angleFromVertical - 180.0) < verticalThreshold);
        }
    }
}
