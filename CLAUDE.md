# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## About the App

NoFlatRotate is an Android app that prevents unwanted screen rotation when a device is laid flat. It watches the accelerometer and, when the device is near-horizontal, locks rotation to whatever orientation the user was last in. When picked back up past the unlock threshold, auto-rotation is restored.

## Build & Test Commands

Standard Gradle wrapper, Groovy DSL (not Kotlin DSL). `build.bat` at the repo root is intentionally empty — use `gradlew` directly.

On Windows the wrapper needs `JAVA_HOME` pointing at a JDK 17+ (Android Studio's bundled JBR works: `C:\Program Files\Android\Android Studio\jbr`).

```bash
./gradlew assembleDebug
./gradlew assembleRelease     # uses R8 (minifyEnabled true); release signing is not configured

# Tests — Robolectric on the JVM, no device needed. AGP 9 splits tests by variant:
./gradlew :app:testDebugUnitTest
./gradlew :app:testDebugUnitTest --tests com.truffulatree.noflatrotate.RotationServiceTest
./gradlew :app:testDebugUnitTest --tests "com.truffulatree.noflatrotate.RotationServiceTest.shouldBeFlat_hysteresisPreventsBouncing"

./gradlew :app:lintDebug
```

Java 18 source/target. Min SDK 24, compile/target SDK 36. AGP pinned in `gradle/libs.versions.toml`. `buildFeatures.buildConfig = true` is on so `BuildConfig.DEBUG` is generated — debug logs in `RotationService` are gated behind it.

## Architecture

Three Java classes, no architectural framework (no ViewModel, no DI, no Compose). State lives in `SharedPreferences` and on the `RotationService` instance.

- **`RotationService.java`** — Foreground service implementing `SensorEventListener`. The math and decision logic are exposed as package-private methods so tests exercise production code directly:
  - `computeAngleFromVertical(x, y, z)` (static) — returns degrees from horizontal (face-up and face-down both read as 0°), or `-1` if `magnitude < MIN_MAGNITUDE`.
  - `shouldBeFlat(angle, currentlyFlat, flatThreshold, verticalThreshold)` (static) — hysteresis decision. Pure.
  - `evaluateRawSample(x, y, z)` — applies the decision to one raw accelerometer sample and updates `deviceInFlatMode`. Pure-ish: no system writes, no Context required, so tests can call it on a bare `new RotationService()`. Called by `onSensorChanged`.
  - `notificationTextResource(permissionOk)` (static) — selects the normal or "permission missing" string.

  Locks via `Settings.System.USER_ROTATION` + `ACCELEROMETER_ROTATION=0`; unlocks by setting `ACCELEROMETER_ROTATION=1`. Receives `ACTION_CONFIG_CHANGED` broadcasts to reload thresholds without restart. When `Settings.System.canWrite()` returns false at action time, the foreground notification text is updated.

  **No smoothing or debounce on the accelerometer input — by design.** The whole point of the app is to lock rotation the *instant* the device is laid flat, so any filtering that delays the decision would defeat the purpose. Hysteresis (different flat/unlock thresholds) is the sole jitter defence; the gap between thresholds must not be collapsed.

  **`rotationPreviouslyLocked` is persisted** to `SharedPreferences` under `KEY_ROTATION_PREVIOUSLY_LOCKED`. The service-process-local flag and the system-wide `ACCELEROMETER_ROTATION` setting have different lifetimes — the latter survives reboots, OOM kills, force-stop, and app updates while the former does not. Without persistence, after any such event the service forgets it locked rotation and never re-enables it, leaving the user stuck with rotation disabled. Two invariants hold the contract together:
  - `setRotationPreviouslyLocked(boolean)` is the only mutator; it writes to both memory and prefs.
  - The flag flips **only after a successful `Settings.System.putInt`** — never before, never on a failed write. If `canWrite()` returns false the persisted flag stays true so the next sample (or a future service instance) can retry.

  `canWriteSettingsSupplier` is a `BooleanSupplier` field used everywhere that previously called `Settings.System.canWrite(...)` directly. It exists because Robolectric 4.16's `ShadowSettings` has no `setCanWrite` — tests swap the supplier to simulate "permission revoked." Production never reassigns it.

- **`MainActivity.java`** — Settings UI. Three persisted prefs: `KEY_SERVICE_ENABLED` (master on/off — Switch in the UI), `KEY_START_ON_BOOT`, and the two thresholds. `clampUnlockThreshold(flat, unlock)` is the pure helper enforcing `unlock >= flat + MIN_HYSTERESIS_GAP` (default 5°). Permission flow: `POST_NOTIFICATIONS` (runtime, Android 13+) via `ActivityResultLauncher`, then `WRITE_SETTINGS` via `Settings.ACTION_MANAGE_WRITE_SETTINGS`. `WRITE_SETTINGS` cannot be requested via the normal runtime dialog — the system-settings flow is mandatory, not a workaround. Writes prefs and broadcasts `ACTION_CONFIG_CHANGED` to the service.

- **`BootReceiver.java`** — Receives `BOOT_COMPLETED` *and* `MY_PACKAGE_REPLACED`. The pure `shouldStartService(startOnBoot, serviceEnabled, canWriteSettings)` helper gates the start; all three must be true. Tested directly.

`RotationService` is the only place that touches `Settings.System`. Everything else just sets prefs and lets the service react.

### Service constraints

- The foreground notification is mandatory (Android 8+).
- Manifest declares `foregroundServiceType="specialUse"` with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. No other FGS type fits sensor-driven internal logic. Play Console requires written justification for `specialUse` at submission.
- On Android 14+, `startForeground` is called with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` explicitly; older API levels use the two-arg overload.
- `windowManager.getDefaultDisplay().getRotation()` is deprecated but kept on purpose — `getDisplay()` requires a visual context (Activity), which a Service is not. The comment in `getCurrentRotation()` documents this.

### Threading model

All mutable service state lands on the main thread today (sensor callbacks come on the main looper because no Handler is passed to `registerListener`; the config-change `BroadcastReceiver` also fires on the main thread). Fields like `flatThresholdDegrees`, `deviceInFlatMode`, `lastStableRotation`, `rotationPreviouslyLocked` are still marked `volatile` so the invariant survives a future move to a `HandlerThread` without subtle visibility bugs.

## Testing

Robolectric only. Tests run on `@Config(sdk = {N, P, TIRAMISU})` (24, 28, 33) so SDK-version branches in `Build.VERSION` get exercised.

Tests call the production static helpers directly — there is **no duplicated logic** between `RotationServiceTest` and `RotationService`. If you change the angle math or hysteresis, the existing tests cover it; you do not need to mirror anything.

`BootReceiverTest` and `MainActivityTest` each test their pure decision helper (`shouldStartService`, `clampUnlockThreshold`) directly, plus Robolectric integration tests for the receiver-and-prefs path. Total: ~177 tests across the three SDK levels.

The persistence-recovery tests build a real service via `Robolectric.buildService(RotationService.class).create()` (see `buildLiveService()` in `RotationServiceTest`). That path needs `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` granted on the shadow application — AndroidX `ContextCompat.registerReceiver` enforces it on `RECEIVER_NOT_EXPORTED`. The production AAR manifest merger provides it automatically; tests grant it explicitly.
