# AetherPlayer

Device-owner kiosk controller that launches Spotify in lock task mode.

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
