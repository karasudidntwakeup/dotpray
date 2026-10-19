# dotpray

A home-screen widget for Nothing phones: the next prayer and a live countdown,
drawn in Nothing's dot-matrix type and monochrome palette rather than the usual
generic Android widget.

## What it shows

**Full (4×2)** — the next prayer, a live countdown, a segmented progress bar for
the current prayer window, the hijri date, and all five times of the day.

**Compact (2×2 and smaller)** — the countdown, the next prayer with its time, all
five times on one line, and the hijri date. Cells too short for that drop the
extra rows instead of clipping them.

## How it works

- Prayer times come from the [Aladhan API](https://aladhan.com/prayer-times-api)
  (method 3, Muslim World League; Hanafi). Location is detected from `ipwho.is`
  and re-detected on each refresh.
- The countdown is a host-side `Chronometer`, so there are no per-minute wakeups.
  Alarms only mark the boundaries: the flip at each prayer (exact,
  allow-while-idle, with a short safety re-render behind it) and the two
  countdown format switches. A 30-minute repaint bounds a system light/dark
  switch — and re-arms the alarms.
- The rounded panel and the segmented bar are painted to bitmaps: widgets cannot
  inflate custom views, so both travel to the launcher as pixels.
- Light and dark palettes follow the system: a white panel (`#FFFFFF`) with
  near-black ink in light mode, the Nothing grey panel (`#151515`) with white ink
  in dark. The corner radii match Nothing's own widgets, and Nothing Red
  (`#FF3030`) is used exactly once per view.

Widget only: no activity, no launcher icon, no settings screen, no notifications.

## Privacy and security

- **What leaves the device:** the location lookup (`ipwho.is`) and the timings
  request (`api.aladhan.com`) — coordinates and the date, nothing else. No
  analytics, no crash reporting, no accounts.
- **What is stored:** the fetched timings, the hijri date, and the last known
  coordinates, in the app's private `SharedPreferences`. The app sets
  `allowBackup="false"`, so they are never copied off the device by backup.
- **No runtime permissions.** Network, boot and exact-alarm access are all
  install-time, so the app never asks you for anything.
- **Exported surface:** only the widget provider is exported, because the
  system delivers `APPWIDGET_UPDATE` to it — and it acts only on widget ids it
  actually owns. The boundary alarms go to a separate, unexported receiver, so
  no other app can fire them. The refresh service is unexported and bound by
  `BIND_JOB_SERVICE`.
- **Transport:** HTTPS only (`usesCleartextTraffic="false"`), with immutable
  `PendingIntent`s.
- The widget is home-screen only (`widgetCategory="home_screen"`), so it is
  never drawn on the lock screen.

## Releasing

Release builds are signed from `keystore.properties`, which is gitignored:

```properties
storeFile=/absolute/path/to/dotpray-release.p12
storePassword=…
keyAlias=dotpray
keyPassword=…
```

Keep the keystore and its password safe: lose them and you can no longer ship
updates that install over an existing release build. Without the file,
`assembleRelease` still works and produces an unsigned APK.

## Build

```sh
ANDROID_HOME=$HOME/Android/Sdk ./gradlew :app:assembleDebug
```

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then add the widget from the launcher's widget picker.

### Nothing phones: background network

Nothing OS blocks background network access for widget-only apps, so the first
fetch never lands and the widget sits on its placeholder. Whitelist it:

```sh
UID=$(adb shell cmd package list packages -U dev.karasu.ndotprayer | sed 's/.*uid://')
adb shell cmd netpolicy add restrict-background-whitelist "$UID"
adb shell cmd netpolicy add app-idle-whitelist "$UID"
```

The application id stays `dev.karasu.ndotprayer` even though the app is called
dotpray.

### Fonts

`ndot55.otf` and `ndot57.otf` are the NDot faces that give the widget its
Nothing look. They are third-party typefaces bundled here for convenience; the
rights remain with their author, so replace or remove them if you redistribute
this under different terms.
