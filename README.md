# NoFlatRotate

An Android app that prevents unwanted screen rotation when your device is laid flat.

## The Problem

Ever had your phone rotate to landscape just as you set it down on a table? This app solves that annoyance by automatically locking screen rotation when your device is nearly horizontal, then re-enabling it when you pick it back up.

## Features

- **Smart Rotation Lock**: Automatically locks rotation when the device is flat (within 20° of horizontal by default)
- **Hysteresis**: Uses separate thresholds for entering and exiting flat mode to prevent jittery behavior
- **Preserves Orientation**: Remembers your last screen orientation before going flat, so it locks to that orientation (not just portrait)
- **Start on Boot**: Optionally starts automatically when your device boots
- **Configurable Thresholds**: Adjust the flat detection angle (default: 20°) and unlock angle (default: 30°) to suit your preferences
- **Lightweight**: Runs as a foreground service with minimal battery impact

## Requirements

- Android 7.0 (API 24) or higher
- Permission to modify system settings (for controlling rotation)

## Installation

1. Download the APK from the [Releases](../../releases) page
2. Install the APK on your device
3. Open the app and grant the "Modify System Settings" permission when prompted
4. The service will start automatically

## Usage

Once permissions are granted, the app runs in the background. You'll see a persistent notification indicating the service is active.

### Settings

Open the app to configure:

- **Start on boot**: Toggle whether the service starts automatically when your device boots (default: enabled)
- **Flat threshold**: The angle (in degrees) at which the device is considered "flat" and rotation locks (default: 20°)
- **Unlock threshold**: The angle (in degrees) at which the device exits "flat mode" and rotation unlocks (default: 30°)

The difference between these two thresholds creates a "hysteresis zone" that prevents rapid toggling when the device is near the threshold angle.

## How It Works

1. The app monitors the device's accelerometer to detect orientation
2. When the device is tilted less than the flat threshold from horizontal, rotation is locked to the current orientation
3. When the device is tilted more than the unlock threshold from horizontal, auto-rotation is re-enabled
4. The app remembers your orientation just before going flat, ensuring you don't get locked into the wrong orientation

## Building from Source

```bash
# Clone the repository
git clone https://github.com/yourusername/NoFlatRotate.git
cd NoFlatRotate

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run tests
./gradlew test
```

## Permissions

- **WRITE_SETTINGS**: Required to control screen rotation
- **FOREGROUND_SERVICE**: Required to run the background service
- **POST_NOTIFICATIONS**: Required for the service notification (Android 13+)
- **RECEIVE_BOOT_COMPLETED**: Required for start-on-boot functionality

## Buy Me a Coffee

If you find this app useful, consider supporting development:

[![PayPal](https://img.shields.io/badge/PayPal-Donate-blue?logo=paypal)](https://paypal.me/strepto/5)

[☕ Buy me a coffee via PayPal](https://paypal.me/strepto/5)

## License

This project is licensed under the GNU General Public License v3.0 - see the [LICENSE](LICENSE) file for details.

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
