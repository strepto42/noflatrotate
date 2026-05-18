# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About the App

NoFlatRotate is an Android app that prevents unwanted screen rotation when a device is laid flat on a table. It watches the accelerometer and, when the device is near-horizontal, locks rotation to whatever orientation the user was last in. When the device is picked back up past the unlock threshold, auto-rotation is restored.

## Build & Test Commands

Standard Gradle wrapper, Groovy DSL (not Kotlin DSL). `build.bat` at the repo root is intentionally empty — use `gradlew` directly.

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease     # uses R8 (minifyEnabled true); release signing is not configured

# Tests — Robolectric on the JVM, no device needed
./gradlew test
./gradlew test --tests com.truffulatree.noflatrotate.RotationServiceTest
./gradlew test --tests "com.truffulatree.noflatrotate.RotationServiceTest.flatDetection_entersFlatModeAt19Degrees"

./gradlew lint
```

Java 18 source/target compatibility. Min SDK 24, compile/target SDK 36. AGP version is pinned in `gradle/libs.versions.toml`.

## Architecture

Three Java classes, no architectural framework (no ViewModel, no DI, no Compose). State lives in `SharedPreferences` and on the `RotationService` instance.

- **`RotationService.java`** — Foreground service implementing `SensorEventListener`. Computes tilt as `acos(|z| / magnitude) * 180/π`. Hysteresis is intentional: enters flat at `flatThresholdDegrees` (default 20°), exits at `verticalThresholdDegrees` (default 30°). The gap between the two thresholds prevents jitter at the boundary — do not collapse them into one threshold. Locks via `Settings.System.USER_ROTATION` + `ACCELEROMETER_ROTATION=0`; unlocks by setting `ACCELEROMETER_ROTATION=1`. Receives `ACTION_CONFIG_CHANGED` broadcasts to reload thresholds without restart.

- **`MainActivity.java`** — Settings UI. Handles the two-step permission dance: `POST_NOTIFICATIONS` (runtime, Android 13+) via `ActivityResultLauncher`, then `WRITE_SETTINGS` via `Settings.ACTION_MANAGE_WRITE_SETTINGS`. `WRITE_SETTINGS` cannot be requested via the normal runtime permission dialog — the flow that navigates to system settings is mandatory, not a workaround. Writes prefs and broadcasts `ACTION_CONFIG_CHANGED` to the service.

- **`BootReceiver.java`** — Starts `RotationService` on `BOOT_COMPLETED` when `KEY_START_ON_BOOT` is true (defaults to true).

`RotationService` is the only place that talks to `Settings.System`. Everything else just sets preferences and lets the service react.

### Service constraints

- The foreground notification is mandatory (Android 8+) — removing `startForeground()` will crash on modern devices.
- The manifest declares `foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. This was a deliberate choice; changing the type requires updating both the manifest property and the matching `FOREGROUND_SERVICE_*` permission.
- `windowManager.getDefaultDisplay().getRotation()` is deprecated but kept on purpose — `getDisplay()` requires a visual context (Activity), which a Service is not. The comment in `getCurrentRotation()` documents this.

## Testing

Robolectric only (`@Config(sdk = {Build.VERSION_CODES.P})`). All "unit" tests run on the JVM with a faked Android framework.

**Important:** `RotationServiceTest` does not call into `RotationService.onSensorChanged()`. Instead, it has private helper methods (`calculateAngleFromVertical`, `calculateShouldBeFlat`) that re-implement the production formulas. **If you change the angle math or hysteresis logic in `RotationService`, you must mirror the change in the test helpers** or the tests will silently keep passing against stale logic. This is a known weakness in the current test design.

Threshold constants (`FLAT_THRESHOLD_DEGREES`, `VERTICAL_THRESHOLD_DEGREES`, `GRAVITY`) are duplicated in the test file — keep them in sync with `MainActivity.DEFAULT_*` if defaults change.
