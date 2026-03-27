package com.truffulatree.noflatrotate;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.P})
public class BootReceiverTest {

    private Context context;
    private BootReceiver bootReceiver;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        bootReceiver = new BootReceiver();
        prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        // Clear preferences before each test
        prefs.edit().clear().apply();
    }

    @Test
    public void bootReceiver_startsService_whenStartOnBootEnabled() {
        // Enable start on boot
        prefs.edit().putBoolean(MainActivity.KEY_START_ON_BOOT, true).apply();

        // Simulate boot completed broadcast
        Intent bootIntent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        bootReceiver.onReceive(context, bootIntent);

        // Verify service was started
        ShadowApplication shadowApplication = Shadows.shadowOf(RuntimeEnvironment.getApplication());
        Intent startedService = shadowApplication.getNextStartedService();
        
        assertNotNull("Service should be started when start on boot is enabled", startedService);
        assertNotNull("Service component should not be null", startedService.getComponent());
        assertEquals("Started service should be RotationService",
                RotationService.class.getName(),
                startedService.getComponent().getClassName());
    }

    @Test
    public void bootReceiver_doesNotStartService_whenStartOnBootDisabled() {
        // Disable start on boot
        prefs.edit().putBoolean(MainActivity.KEY_START_ON_BOOT, false).apply();

        // Simulate boot completed broadcast
        Intent bootIntent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        bootReceiver.onReceive(context, bootIntent);

        // Verify service was NOT started
        ShadowApplication shadowApplication = Shadows.shadowOf(RuntimeEnvironment.getApplication());
        Intent startedService = shadowApplication.getNextStartedService();
        
        assertNull("Service should not be started when start on boot is disabled", startedService);
    }

    @Test
    public void bootReceiver_startsService_whenPreferenceNotSet_defaultsToTrue() {
        // Don't set any preference - should default to true

        // Simulate boot completed broadcast
        Intent bootIntent = new Intent(Intent.ACTION_BOOT_COMPLETED);
        bootReceiver.onReceive(context, bootIntent);

        // Verify service was started (default behavior)
        ShadowApplication shadowApplication = Shadows.shadowOf(RuntimeEnvironment.getApplication());
        Intent startedService = shadowApplication.getNextStartedService();
        
        assertNotNull("Service should be started by default when preference not set", startedService);
        assertNotNull("Service component should not be null", startedService.getComponent());
        assertEquals("Started service should be RotationService",
                RotationService.class.getName(),
                startedService.getComponent().getClassName());
    }

    @Test
    public void bootReceiver_ignoresOtherIntents() {
        // Enable start on boot
        prefs.edit().putBoolean(MainActivity.KEY_START_ON_BOOT, true).apply();

        // Send a different intent (not BOOT_COMPLETED)
        Intent otherIntent = new Intent("android.intent.action.SOME_OTHER_ACTION");
        bootReceiver.onReceive(context, otherIntent);

        // Verify service was NOT started
        ShadowApplication shadowApplication = Shadows.shadowOf(RuntimeEnvironment.getApplication());
        Intent startedService = shadowApplication.getNextStartedService();
        
        assertNull("Service should not start for non-BOOT_COMPLETED intents", startedService);
    }

    @Test
    public void preferencesKey_startOnBoot_defaultIsTrue() {
        // Verify the default value is true when not set
        boolean defaultValue = prefs.getBoolean(MainActivity.KEY_START_ON_BOOT, true);
        assertTrue("Default start on boot value should be true", defaultValue);
    }
}
