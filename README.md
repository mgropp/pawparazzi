# Pawparazzi

Android app that watches the back camera with MediaPipe EfficientDet-Lite0, plays a sound for each newly
appearing cat or dog and saves a full-resolution photo to `Pictures/Pawparazzi/`.

## Build

```
./gradlew test                 # JVM unit tests (core:detection, core:settings, actions)
./gradlew :app:assembleDebug
./gradlew connectedAndroidTest # needs a device; camera tests need a real one
```

The detector model is fetched into `camera/src/main/assets/` by the `downloadModel` Gradle task on first build.

## Usage

Open the app, grant camera and notification permission, tap **Start**. Detection runs in a foreground service;
stop it from the app or the notification. There is no auto-start after reboot, and detection stays off after a
process death until started manually.

Photos are viewed in the phone's gallery. Older photos beyond the configured storage cap are deleted automatically
(only those saved by this installation).

## Keeping Pawparazzi alive

Android and vendor ROMs aggressively stop background apps. Do all of the following on the target phone, then check
`dontkillmyapp.com` for your model.

- **Stock Android / Pixel**: Settings > Apps > Pawparazzi > App battery usage > *Unrestricted*. Accept the in-app
  battery-optimization prompt. (Pixel 4a menu paths: to be confirmed during hardening.)
- **Samsung**: Settings > Battery > Background usage limits: remove Pawparazzi from *Sleeping apps* and
  *Deep sleeping apps*, add it to *Never sleeping apps*. Set battery usage to *Unrestricted*.
- **Xiaomi / MIUI**: Settings > Apps > Manage apps > Pawparazzi > Autostart: on; Battery saver: *No restrictions*;
  lock the app in the recent-apps list.
- **Huawei / EMUI**: Settings > Battery > App launch > Pawparazzi: *Manage manually*, enable auto-launch, secondary
  launch and run in background.
- **OnePlus / OxygenOS**: Settings > Battery > Battery optimization > Pawparazzi: *Don't optimize*; enable
  *Allow background activity* and *Allow auto-launch*; lock the app in recents.
- **Oppo / ColorOS**: Settings > Battery > Pawparazzi: allow background running and auto-launch; lock the app in
  recents.
- Also whitelist the app in any "memory cleaner" or "phone manager" app.

## Heat

The app throttles analysis to 1 FPS at thermal status `SEVERE` and pauses the camera at `CRITICAL`.

## Licenses

See `NOTICE`.
