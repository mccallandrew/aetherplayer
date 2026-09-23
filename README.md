# AetherPlayer

Device-owner kiosk controller that launches Spotify in lock task mode.
Bluetooth pairing and Wi-Fi connection are handled in-app. While those
screens are open, Settings / Fast Pair / captive-portal packages are
temporarily lock-task allowlisted so system UI is not blocked.

## Build and install

```bash
./gradlew build
./gradlew installDebug
```

AetherPlayer must be the device owner. The device cannot already have an account, and this only needs to be done once:

```bash
adb shell dpm set-device-owner \
  com.mccallandrew.aetherplayer/.AetherDeviceAdminReceiver
```

Grant notification-listener access so the home screen can read Spotify’s now-playing session (also only once):

```bash
adb shell cmd notification allow_listener \
  com.mccallandrew.aetherplayer/.SpotifyNotificationListener
```

## Kiosk commands

Start kiosk mode:

```bash
adb shell am broadcast \
  -a com.mccallandrew.aetherplayer.START_KIOSK \
  -n com.mccallandrew.aetherplayer/.KioskCommandReceiver
```

Exit kiosk mode and restore the original Home app:

```bash
adb shell am broadcast \
  -a com.mccallandrew.aetherplayer.EXIT_KIOSK \
  -n com.mccallandrew.aetherplayer/.KioskCommandReceiver
```
