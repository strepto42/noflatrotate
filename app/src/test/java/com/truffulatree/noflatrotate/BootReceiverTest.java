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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Objects;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {Build.VERSION_CODES.N, Build.VERSION_CODES.P, Build.VERSION_CODES.TIRAMISU})
public class BootReceiverTest {

    private Context context;
    private BootReceiver bootReceiver;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        bootReceiver = new BootReceiver();
        prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    // ==================== Pure decision logic ====================

    @Test
    public void shouldStartService_allGatesOpen_returnsTrue() {
        assertTrue(BootReceiver.shouldStartService(true, true, true));
    }

    @Test
    public void shouldStartService_startOnBootDisabled_returnsFalse() {
        assertFalse(BootReceiver.shouldStartService(false, true, true));
    }

    @Test
    public void shouldStartService_serviceDisabled_returnsFalse() {
        assertFalse(BootReceiver.shouldStartService(true, false, true));
    }

    @Test
    public void shouldStartService_cannotWriteSettings_returnsFalse() {
        assertFalse(BootReceiver.shouldStartService(true, true, false));
    }

    @Test
    public void shouldStartService_allGatesClosed_returnsFalse() {
        assertFalse(BootReceiver.shouldStartService(false, false, false));
    }

    // ==================== Integration via Robolectric ====================
    // Robolectric defaults Settings.System.canWrite() to true, so these
    // exercise the start-on-boot and service-enabled prefs.

    @Test
    public void onReceive_startsService_whenAllPrefsEnabled() {
        prefs.edit()
                .putBoolean(MainActivity.KEY_START_ON_BOOT, true)
                .putBoolean(MainActivity.KEY_SERVICE_ENABLED, true)
                .apply();

        bootReceiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        Intent started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull("Service should start when all gates open", started);
        assertEquals(RotationService.class.getName(), Objects.requireNonNull(started.getComponent()).getClassName());
    }

    @Test
    public void onReceive_doesNotStart_whenServiceDisabled() {
        prefs.edit()
                .putBoolean(MainActivity.KEY_START_ON_BOOT, true)
                .putBoolean(MainActivity.KEY_SERVICE_ENABLED, false)
                .apply();

        bootReceiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test
    public void onReceive_doesNotStart_whenStartOnBootDisabled() {
        prefs.edit()
                .putBoolean(MainActivity.KEY_START_ON_BOOT, false)
                .putBoolean(MainActivity.KEY_SERVICE_ENABLED, true)
                .apply();

        bootReceiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
    }

    @Test
    public void onReceive_handlesMyPackageReplaced() {
        prefs.edit()
                .putBoolean(MainActivity.KEY_START_ON_BOOT, true)
                .putBoolean(MainActivity.KEY_SERVICE_ENABLED, true)
                .apply();

        bootReceiver.onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));

        Intent started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull("Service should restart on MY_PACKAGE_REPLACED", started);
        assertEquals(RotationService.class.getName(), Objects.requireNonNull(started.getComponent()).getClassName());
    }

    @Test
    public void onReceive_ignoresUnknownAction() {
        prefs.edit()
                .putBoolean(MainActivity.KEY_START_ON_BOOT, true)
                .putBoolean(MainActivity.KEY_SERVICE_ENABLED, true)
                .apply();

        bootReceiver.onReceive(context, new Intent("android.intent.action.SOME_OTHER_ACTION"));

        ShadowApplication shadow = Shadows.shadowOf(RuntimeEnvironment.getApplication());
        assertNull(shadow.getNextStartedService());
    }

    @Test
    public void onReceive_startsByDefault_whenPrefsUnset() {
        // No prefs set — both default to true, canWrite true under Robolectric,
        // so the service should start.
        bootReceiver.onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));

        Intent started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertNotNull(started);
    }
}
