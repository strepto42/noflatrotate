package com.truffulatree.noflatrotate;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private Button permissionButton;
    private TextView permissionGrantedTextView;

    // Modern way to handle permission requests
    private final ActivityResultLauncher<String> requestPermissionLauncher = 
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                // Notification permission granted. Now check for Write Settings.
                checkAndRequestWriteSettingsPermission();
            } else {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show();
                // App can still function, but service notification might not appear on Android 13+
                // Proceed to check Write Settings permission anyway
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

        TextView welcomeTextView = findViewById(R.id.welcome_text_view);
        TextView explanationTextView = findViewById(R.id.explanation_text_view);
        permissionButton = findViewById(R.id.permission_button);
        permissionGrantedTextView = findViewById(R.id.permission_granted_text_view);

        welcomeTextView.setText(R.string.welcome_message);
        explanationTextView.setText(R.string.explanation);

        // Start permission check flow
        checkAndRequestInitialPermissions();
    }

    private void checkAndRequestInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                // Notification permission already granted, check Write Settings
                checkAndRequestWriteSettingsPermission();
            } else {
                // Request POST_NOTIFICATIONS permission
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            // On older versions, no runtime POST_NOTIFICATIONS permission needed
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
        Intent serviceIntent = new Intent(this, RotationService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }
}
