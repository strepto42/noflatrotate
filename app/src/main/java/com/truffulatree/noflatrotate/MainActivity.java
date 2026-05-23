package com.truffulatree.noflatrotate;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "NoFlatRotatePrefs";
    public static final String KEY_SERVICE_ENABLED = "service_enabled";
    public static final String KEY_START_ON_BOOT = "start_on_boot";
    public static final String KEY_FLAT_THRESHOLD = "flat_threshold";
    public static final String KEY_VERTICAL_THRESHOLD = "vertical_threshold";

    public static final int DEFAULT_FLAT_THRESHOLD = 20;
    public static final int DEFAULT_VERTICAL_THRESHOLD = 30;

    @VisibleForTesting
    public static final int MIN_FLAT_THRESHOLD = 5;
    @VisibleForTesting
    public static final int MIN_HYSTERESIS_GAP = 5;

    private Button permissionButton;
    private TextView permissionGrantedTextView;
    private SwitchCompat serviceEnabledSwitch;
    private SwitchCompat startOnBootSwitch;
    private SeekBar flatThresholdSeekBar;
    private SeekBar verticalThresholdSeekBar;
    private TextView flatThresholdValue;
    private TextView verticalThresholdValue;

    private SharedPreferences prefs;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (!isGranted) {
                    Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show();
                }
                checkAndRequestWriteSettingsPermission();
            });

    private final ActivityResultLauncher<Intent> writeSettingsLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (Settings.System.canWrite(this)) {
                    updateUIPermissionGranted();
                    startRotationServiceIfEnabled();
                } else {
                    updateUIPermissionNeeded();
                    Toast.makeText(this, R.string.write_settings_permission_denied, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        TextView welcomeTextView = findViewById(R.id.welcome_text_view);
        TextView explanationTextView = findViewById(R.id.explanation_text_view);
        permissionButton = findViewById(R.id.permission_button);
        permissionGrantedTextView = findViewById(R.id.permission_granted_text_view);
        serviceEnabledSwitch = findViewById(R.id.service_enabled_switch);
        startOnBootSwitch = findViewById(R.id.start_on_boot_switch);
        flatThresholdSeekBar = findViewById(R.id.flat_threshold_seekbar);
        verticalThresholdSeekBar = findViewById(R.id.vertical_threshold_seekbar);
        flatThresholdValue = findViewById(R.id.flat_threshold_value);
        verticalThresholdValue = findViewById(R.id.vertical_threshold_value);

        welcomeTextView.setText(R.string.welcome_message);
        explanationTextView.setText(R.string.explanation);

        setupPreferences();
        checkAndRequestInitialPermissions();
    }

    private void setupPreferences() {
        boolean serviceEnabled = prefs.getBoolean(KEY_SERVICE_ENABLED, true);
        boolean startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, true);
        int flatThreshold = prefs.getInt(KEY_FLAT_THRESHOLD, DEFAULT_FLAT_THRESHOLD);
        int verticalThreshold = prefs.getInt(KEY_VERTICAL_THRESHOLD, DEFAULT_VERTICAL_THRESHOLD);

        serviceEnabledSwitch.setChecked(serviceEnabled);
        serviceEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_SERVICE_ENABLED, isChecked).apply();
            if (isChecked) {
                startRotationServiceIfPermitted();
            } else {
                stopRotationService();
            }
        });

        startOnBootSwitch.setChecked(startOnBoot);
        startOnBootSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(KEY_START_ON_BOOT, isChecked).apply());

        flatThresholdSeekBar.setProgress(flatThreshold);
        flatThresholdValue.setText(getString(R.string.degree_format, flatThreshold));
        flatThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int newFlat = Math.max(MIN_FLAT_THRESHOLD, progress);
                if (newFlat != progress) {
                    seekBar.setProgress(newFlat);
                    return; // setProgress will re-enter with the clamped value
                }
                flatThresholdValue.setText(getString(R.string.degree_format, newFlat));
                if (fromUser) {
                    int currentUnlock = verticalThresholdSeekBar.getProgress();
                    int newUnlock = clampUnlockThreshold(newFlat, currentUnlock);
                    prefs.edit()
                            .putInt(KEY_FLAT_THRESHOLD, newFlat)
                            .putInt(KEY_VERTICAL_THRESHOLD, newUnlock)
                            .apply();
                    if (newUnlock != currentUnlock) {
                        verticalThresholdSeekBar.setProgress(newUnlock);
                    }
                    notifyServiceOfConfigChange();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        verticalThresholdSeekBar.setProgress(verticalThreshold);
        verticalThresholdValue.setText(getString(R.string.degree_format, verticalThreshold));
        verticalThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int currentFlat = flatThresholdSeekBar.getProgress();
                int clamped = clampUnlockThreshold(currentFlat, progress);
                if (clamped != progress) {
                    seekBar.setProgress(clamped);
                    return; // setProgress re-enters with clamped value
                }
                verticalThresholdValue.setText(getString(R.string.degree_format, clamped));
                if (fromUser) {
                    prefs.edit().putInt(KEY_VERTICAL_THRESHOLD, clamped).apply();
                    notifyServiceOfConfigChange();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    /**
     * Enforces unlock >= flat + MIN_HYSTERESIS_GAP. Returns the (possibly
     * bumped) unlock value. Pure — exposed for testing.
     */
    @VisibleForTesting
    public static int clampUnlockThreshold(int flat, int unlock) {
        int minUnlock = flat + MIN_HYSTERESIS_GAP;
        return Math.max(minUnlock, unlock);
    }

    private void notifyServiceOfConfigChange() {
        Intent intent = new Intent(RotationService.ACTION_CONFIG_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void checkAndRequestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                checkAndRequestWriteSettingsPermission();
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            checkAndRequestWriteSettingsPermission();
        }
    }

    private void checkAndRequestWriteSettingsPermission() {
        if (Settings.System.canWrite(this)) {
            updateUIPermissionGranted();
            startRotationServiceIfEnabled();
        } else {
            updateUIPermissionNeeded();
        }
    }

    private void updateUIPermissionGranted() {
        permissionButton.setVisibility(View.GONE);
        permissionGrantedTextView.setVisibility(View.VISIBLE);
        permissionGrantedTextView.setText(R.string.permission_granted_message);
    }

    private void updateUIPermissionNeeded() {
        permissionButton.setVisibility(View.VISIBLE);
        permissionGrantedTextView.setVisibility(View.GONE);
        permissionButton.setText(R.string.grant_permission_button);
        permissionButton.setOnClickListener(v -> requestWriteSettingsPermission());
    }

    private void requestWriteSettingsPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        writeSettingsLauncher.launch(intent);
    }

    /**
     * Called from the permission-grant flow. Respects the service-enabled
     * toggle so we don't surprise the user by starting a service they
     * explicitly disabled.
     */
    private void startRotationServiceIfEnabled() {
        if (!prefs.getBoolean(KEY_SERVICE_ENABLED, true)) return;
        startServiceIntent();
    }

    /**
     * Called from the service-enabled switch. Only starts when permissions
     * are present; otherwise the switch state still persists and the service
     * will start once the user grants permission.
     */
    private void startRotationServiceIfPermitted() {
        if (!Settings.System.canWrite(this)) return;
        startServiceIntent();
    }

    private void startServiceIntent() {
        Intent serviceIntent = new Intent(this, RotationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopRotationService() {
        stopService(new Intent(this, RotationService.class));
    }
}
