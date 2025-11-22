package com.truffulatree.noflatrotate;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.os.Build;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.P})
public class RotationServiceTest {

    private Context context;
    private RotationService service;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        service = new RotationService();
    }

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
        assertEquals("Intent should target correct service class",
                RotationService.class.getName(),
                serviceIntent.getComponent().getClassName());
    }

    @Test
    public void sensorManager_isAvailable() {
        SensorManager sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        assertNotNull("SensorManager should be available", sensorManager);

        // Get the ShadowSensorManager to control the test environment
        ShadowSensorManager shadowSensorManager = shadowOf(sensorManager);

        // 1. Create a fake Sensor object using Robolectric's shadow helper
        Sensor accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);

        // 2. Add the non-null sensor to the shadow manager
        shadowSensorManager.addSensor(accelerometer);

        // 3. Now, when you ask for the default sensor, you will get the one you just added
        Sensor retrievedSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        assertNotNull("Accelerometer sensor should now be available in the test environment", retrievedSensor);
    }

    @Test
    public void onBind_returnsNull() {
        // Service is not designed to be bound, so should return null
        Intent intent = new Intent();
        assertEquals("onBind should return null for unbound service",
                null, service.onBind(intent));
    }
}
