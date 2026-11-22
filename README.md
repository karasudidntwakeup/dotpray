# dotpray

A home-screen widget for Nothing phones: the next prayer and a live countdown,
drawn in Nothing's dot face with the monochrome palette rather than the usual
generic Android widget.

## What it shows

Wide widgets show the next prayer, a live countdown, and the five daily
prayer times. The red rail marks the next prayer. Filled grey rails mark
prayers that have passed, and pale tracks mark later prayers. After Isha,
the Fajr column shows tomorrow's time when it is cached.

The layout adapts to the space the launcher provides. Smaller tiles keep the
countdown, next prayer, and rails in view. The countdown displays `H:MM HRS`
until the last hour, then switches to `MM MIN`.

## Using it

- Tap the widget to refresh the location and timings on demand. While the fetch
  runs, a rotating dot indicator appears while cached times and the countdown
  stay visible.
- When a prayer arrives, a Nothing-styled notification announces it with the
  prayer name, time and a red dot; the widget then settles on the next prayer.
  Tapping the notification refreshes the widget. On Android 13+, the first
  widget tap also asks for notification permission.
- On Nothing Phone (2), the **Pre-prayer reminders** notification category
  starts a three-minute countdown before each prayer. On Nothing OS with
  Android 16, enable **Glyph Progress** and allow dotpray's **Live Updates**
  to see the countdown on the rear Glyph. For the standard notification
  flash, enable **Glyph Interface** and choose a built-in Nothing sound in
  **Settings → Apps → dotpray → Notifications**. Nothing OS uses the sound's
  [matching Glyph pattern](https://support.nothing.tech/hc/en-us/articles/16770201348113-How-do-I-personalise-the-Glyph-Interface-for-notifications).
  The reminder clears at prayer time. Android can delay alarms while idle, and
  a reminder that arrives after prayer time is skipped.

## How it works

- Prayer times come from the [Aladhan API](https://aladhan.com/prayer-times-api)
  (method 3, Muslim World League; Hanafi). Location is detected from `ipwho.is`
  and re-detected on each refresh.
- The countdown uses the launcher's `Chronometer`. The app schedules wakeups
  at prayer boundaries and when the countdown format changes. On Phone (2),
  there is also a wakeup for the pre-prayer reminder.
- Android 12 and later can select from layouts sized for the space reported by
  the launcher and resolve widget colors in the host configuration. Older
  launchers receive portrait and landscape layouts, with a theme-watch job
  for explicit night mode changes.
- The panel, rails and sync indicator use native drawable resources. Light mode
  uses a white panel with a darker red accent for legibility. Dark mode uses
  a charcoal panel with a brighter red accent. The layout uses Nothing's dot
  type for the countdown and system sans for the schedule.
- Refreshes commit the location and timings together. If a request fails, the
  last successful cache remains available. Old days are pruned on a successful
  refresh.

Widget only: no launcher entry and no settings screen. The only activity is an
invisible notification-permission prompt, opened from a widget tap on Android
13+ when notifications are still disabled.

## Privacy and security

- **What leaves the device:** the location lookup (`ipwho.is`) and the timings
  request (`api.aladhan.com`) — coordinates and the date, nothing else. No
  analytics, no crash reporting, no accounts.
- **What is stored:** the fetched timings and the last known coordinates, in the
  app's private `SharedPreferences`. The app sets `allowBackup="false"`, so they
  are never copied off the device by backup.
- **One runtime permission, for the notification.** Network, boot and
  exact-alarm access are install-time. On Android 13+ the first widget tap asks
  for `POST_NOTIFICATIONS`; deny it and the widget still works, only without
  prayer notifications.
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

The countdown uses Nothing's `NDot55All` system family, with a system
sans-serif fallback. The app bundles no fonts.
