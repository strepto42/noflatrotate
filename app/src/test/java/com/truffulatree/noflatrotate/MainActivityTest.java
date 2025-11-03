package com.truffulatree.noflatrotate;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(AndroidJUnit4.class)
@Config(sdk = {Build.VERSION_CODES.P})
public class MainActivityTest {

    private ActivityScenario<MainActivity> scenario;

    @After
    public void tearDown() {
        if (scenario != null) {
            scenario.close();
        }
    }

    @Test
    public void onCreate_initializesUIElements() {
        scenario = ActivityScenario.launch(MainActivity.class);

        scenario.onActivity(activity -> {
            TextView welcomeText = activity.findViewById(R.id.welcome_text_view);
            TextView explanationText = activity.findViewById(R.id.explanation_text_view);
            Button permissionButton = activity.findViewById(R.id.permission_button);
            TextView permissionGrantedText = activity.findViewById(R.id.permission_granted_text_view);

            assertNotNull("Welcome text should not be null", welcomeText);
            assertNotNull("Explanation text should not be null", explanationText);
            assertNotNull("Permission button should not be null", permissionButton);
            assertNotNull("Permission granted text should not be null", permissionGrantedText);

            assertEquals("Welcome message should be set",
                    activity.getString(R.string.welcome_message),
                    welcomeText.getText().toString());
            assertEquals("Explanation should be set",
                    activity.getString(R.string.explanation),
                    explanationText.getText().toString());
        });
    }

    @Test
    public void initialUIState_showsCorrectVisibility() {
        scenario = ActivityScenario.launch(MainActivity.class);

        scenario.onActivity(activity -> {
            TextView welcomeText = activity.findViewById(R.id.welcome_text_view);
            TextView explanationText = activity.findViewById(R.id.explanation_text_view);

            assertEquals("Welcome text should be visible", View.VISIBLE, welcomeText.getVisibility());
            assertEquals("Explanation text should be visible", View.VISIBLE, explanationText.getVisibility());
        });
    }

    @Test
    public void serviceIntent_canBeCreated() {
        scenario = ActivityScenario.launch(MainActivity.class);

        scenario.onActivity(activity -> {
            Intent serviceIntent = new Intent(activity, RotationService.class);
            assertNotNull("Service intent should be created", serviceIntent);
            assertEquals("Intent should target RotationService",
                    RotationService.class.getName(),
                    serviceIntent.getComponent().getClassName());
        });
    }
}
