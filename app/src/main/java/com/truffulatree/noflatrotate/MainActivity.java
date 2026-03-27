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

    private Button permissionButton;
    private TextView permissionGrantedTextView;
    private SwitchCompat startOnBootSwitch;
    private SeekBar flatThresholdSeekBar;
    private SeekBar verticalThresholdSeekBar;
    private TextView flatThresholdValue;
    private TextView verticalThresholdValue;

    private SharedPreferences prefs;

    // Modern way to handle permission requests
    private final ActivityResultLauncher<String> requestPermissionLauncher = 
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                checkAndRequestWriteSettingsPermission();
            } else {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show();
                checkAndRequestWriteSettingsPermission();
            }
        });

    // Modern way to handle write settings permission
    private final ActivityResultLauncher<Intent> writeSettingsLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (Settings.System.canWrite(this)) {
                updateUIPermissionGranted();
                startRotationService();
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

        // Initialize views
        TextView welcomeTextView = findViewById(R.id.welcome_text_view);
        TextView explanationTextView = findViewById(R.id.explanation_text_view);
        permissionButton = findViewById(R.id.permission_button);
        permissionGrantedTextView = findViewById(R.id.permission_granted_text_view);
        startOnBootSwitch = findViewById(R.id.start_on_boot_switch);
        flatThresholdSeekBar = findViewById(R.id.flat_threshold_seekbar);
        verticalThresholdSeekBar = findViewById(R.id.vertical_threshold_seekbar);
        flatThresholdValue = findViewById(R.id.flat_threshold_value);
        verticalThresholdValue = findViewById(R.id.vertical_threshold_value);

        welcomeTextView.setText(R.string.welcome_message);
        explanationTextView.setText(R.string.explanation);

        // Setup preferences UI
        setupPreferences();

        // Start permission check flow
        checkAndRequestInitialPermissions();
    }

    private void setupPreferences() {
        // Load saved values
        boolean startOnBoot = prefs.getBoolean(KEY_START_ON_BOOT, true);
        int flatThreshold = prefs.getInt(KEY_FLAT_THRESHOLD, DEFAULT_FLAT_THRESHOLD);
        int verticalThreshold = prefs.getInt(KEY_VERTICAL_THRESHOLD, DEFAULT_VERTICAL_THRESHOLD);

        // Setup start on boot switch
        startOnBootSwitch.setChecked(startOnBoot);
        startOnBootSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_START_ON_BOOT, isChecked).apply();
        });

        // Setup flat threshold seekbar
        flatThresholdSeekBar.setProgress(flatThreshold);
        flatThresholdValue.setText(getString(R.string.degree_format, flatThreshold));
        flatThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Minimum of 5 degrees
                int value = Math.max(5, progress);
                flatThresholdValue.setText(getString(R.string.degree_format, value));
                if (fromUser) {
                    prefs.edit().putInt(KEY_FLAT_THRESHOLD, value).apply();
                    notifyServiceOfConfigChange();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Setup vertical threshold seekbar
        verticalThresholdSeekBar.setProgress(verticalThreshold);
        verticalThresholdValue.setText(getString(R.string.degree_format, verticalThreshold));
        verticalThresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Minimum of 10 degrees
                int value = Math.max(10, progress);
                verticalThresholdValue.setText(getString(R.string.degree_format, value));
                if (fromUser) {
                    prefs.edit().putInt(KEY_VERTICAL_THRESHOLD, value).apply();
                    notifyServiceOfConfigChange();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void notifyServiceOfConfigChange() {
        // Send broadcast to service to reload config
        Intent intent = new Intent(RotationService.ACTION_CONFIG_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void checkAndRequestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
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
            startRotationService();
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
        intent.setData(Uri.parse("package:" + getPackageName()));
        writeSettingsLauncher.launch(intent);
    }

    private void startRotationService() {
        // Save that the service should be running (for boot receiver)
        prefs.edit().putBoolean(KEY_SERVICE_ENABLED, true).apply();

        Intent serviceIntent = new Intent(this, RotationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}
