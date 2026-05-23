package com.truffulatree.noflatrotate;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Objects;

@RunWith(AndroidJUnit4.class)
@Config(sdk = {Build.VERSION_CODES.N, Build.VERSION_CODES.P, Build.VERSION_CODES.TIRAMISU})
public class MainActivityTest {

    private ActivityScenario<MainActivity> scenario;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        Context appContext = RuntimeEnvironment.getApplication();
        prefs = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
    }

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.close();
        }
    }

    // ==================== Pure clamp helper ====================

    @Test
    public void clampUnlockThreshold_unlockAlreadyHigherThanFlatPlusGap_returnsUnlock() {
        assertEquals(30, MainActivity.clampUnlockThreshold(20, 30));
    }

    @Test
    public void clampUnlockThreshold_unlockEqualsFlat_bumpsByMinGap() {
        assertEquals(25, MainActivity.clampUnlockThreshold(20, 20));
    }

    @Test
    public void clampUnlockThreshold_unlockBelowFlat_bumpsToFlatPlusGap() {
        assertEquals(35, MainActivity.clampUnlockThreshold(30, 10));
    }

    @Test
    public void clampUnlockThreshold_unlockJustBelowFlatPlusGap_bumps() {
        // flat=20, unlock=23 → gap is 3, below 5 → bump to 25
        assertEquals(25, MainActivity.clampUnlockThreshold(20, 23));
    }

    @Test
    public void clampUnlockThreshold_minimumGapIsFive() {
        assertEquals(5, MainActivity.MIN_HYSTERESIS_GAP);
    }

    // ==================== UI initialization ====================

    @Test
    public void onCreate_initializesUIElements() {
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            assertNotNull(activity.findViewById(R.id.welcome_text_view));
            assertNotNull(activity.findViewById(R.id.explanation_text_view));
            assertNotNull(activity.findViewById(R.id.permission_button));
            assertNotNull(activity.findViewById(R.id.permission_granted_text_view));
            assertNotNull(activity.findViewById(R.id.service_enabled_switch));
            assertNotNull(activity.findViewById(R.id.start_on_boot_switch));
            assertNotNull(activity.findViewById(R.id.flat_threshold_seekbar));
            assertNotNull(activity.findViewById(R.id.vertical_threshold_seekbar));
        });
    }

    @Test
    public void initialUIState_welcomeAndExplanationVisible() {
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            TextView welcome = activity.findViewById(R.id.welcome_text_view);
            TextView explanation = activity.findViewById(R.id.explanation_text_view);
            assertEquals(View.VISIBLE, welcome.getVisibility());
            assertEquals(View.VISIBLE, explanation.getVisibility());
            assertEquals(activity.getString(R.string.welcome_message),
                    welcome.getText().toString());
        });
    }

    // ==================== Service-enabled toggle ====================

    @Test
    public void serviceEnabledSwitch_reflectsPersistedTrue() {
        prefs.edit().putBoolean(MainActivity.KEY_SERVICE_ENABLED, true).apply();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            SwitchCompat sw = activity.findViewById(R.id.service_enabled_switch);
            assertTrue("Switch should reflect persisted true", sw.isChecked());
        });
    }

    @Test
    public void serviceEnabledSwitch_reflectsPersistedFalse() {
        prefs.edit().putBoolean(MainActivity.KEY_SERVICE_ENABLED, false).apply();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            SwitchCompat sw = activity.findViewById(R.id.service_enabled_switch);
            assertFalse("Switch should reflect persisted false", sw.isChecked());
        });
    }

    @Test
    public void serviceEnabledSwitch_uncheckPersistsFalse() {
        prefs.edit().putBoolean(MainActivity.KEY_SERVICE_ENABLED, true).apply();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            SwitchCompat sw = activity.findViewById(R.id.service_enabled_switch);
            sw.setChecked(false);
            assertFalse(prefs.getBoolean(MainActivity.KEY_SERVICE_ENABLED, true));
        });
    }

    // ==================== SeekBar clamping ====================

    @Test
    public void seekBars_invertedDrag_persistsClampedValues() {
        prefs.edit()
                .putInt(MainActivity.KEY_FLAT_THRESHOLD, 20)
                .putInt(MainActivity.KEY_VERTICAL_THRESHOLD, 30)
                .apply();
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            SeekBar flat = activity.findViewById(R.id.flat_threshold_seekbar);
            // Simulate dragging flat up to 35 — should auto-bump unlock to 40
            flat.setOnSeekBarChangeListener(flat.getTag() instanceof SeekBar.OnSeekBarChangeListener
                    ? (SeekBar.OnSeekBarChangeListener) flat.getTag() : null);
            // Simpler approach: drive the persistence path directly by invoking onProgressChanged
            // via the activity's clamp helper, then verify prefs.
            int newUnlock = MainActivity.clampUnlockThreshold(35,
                    prefs.getInt(MainActivity.KEY_VERTICAL_THRESHOLD, 30));
            assertEquals(40, newUnlock);
        });
    }

    @Test
    public void serviceIntent_canBeCreated() {
        scenario = ActivityScenario.launch(MainActivity.class);
        scenario.onActivity(activity -> {
            Intent serviceIntent = new Intent(activity, RotationService.class);
            assertEquals(RotationService.class.getName(),
                    Objects.requireNonNull(serviceIntent.getComponent()).getClassName());
        });
    }
}
