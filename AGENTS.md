# AGENTS.md — Halo

## What this project is

An LSPosed module that draws a dynamic-island-style pill inside `com.android.systemui` on an
OPPO A79 5G (ColorOS 13.1, Android 13). One APK: module + settings activity.

## Hard rules

1. **Never hardcode island sizes in dp.** The device reports density 408 while the reference
   device reports 540; dp copied from another build renders a pill shorter than the camera hole.
   Derive everything from `WindowInsets.getDisplayCutout()` and panel-width fractions
   (`Geometry.kt` is the only place that does the arithmetic).
2. **Do not hook OEM class names** beyond the two AOSP-named entry points already in use
   (`NotifCollection`, `CollapsedStatusBarFragment`). Every extra OEM symbol is an OTA breakage.
3. **No proprietary assets or code from the realme/ColorOS SystemUI** — numbers and physical
   measurements only, as recorded in `NOTICE`.
4. **Nothing may throw across a hook boundary.** Every hook body is wrapped; the failure mode we
   accept is "no island", never "broken status bar".
5. **An interceptor must return a value of the target's declared type on every path.** A
   non-exhaustive `when` (or a bare `try`) as the last statement of a lambda is a *statement* in
   Kotlin, so the hook returns `Unit` — handed to a `boolean` method that is SystemUI's crash. Use
   `return@intercept` explicitly; this crashed the status bar twice and put LSPosed in safe mode.
6. **No AndroidX, no Compose, no third-party dependency.** The settings screen and the island are
   drawn with framework views and `Canvas` (`SettingsUi.kt`), because a theme is exactly what an
   OEM re-skins between releases and drawn pixels are not.
7. Scope discipline: this repo touches only the island. Do not add settings pages, themes, or
   scenes that were not asked for; report the idea instead.
8. Do not add `system_server` to the module scope, and do not install a Magisk RRO. Both were
   considered for fullscreen detection and both were rejected — the WindowManager signal replaced
   them.

## Commands

```bash
sh gradlew :app:assembleDebug                    # build
adb -s "$SERIAL" install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" shell su -c 'kill $(pidof com.android.systemui)'   # reload hooks
sh tools/verify.sh                               # logcat + test notification + screenshot + bounds
```

`$SERIAL` is the single attached device by default; the tool scripts accept `SERIAL=` to override.
The release keystore lives **outside** this repository at `../island-keys/keystore.properties`;
without it the release build still configures and comes out unsigned, so a clone is never broken.

## Layout of the code

| file | owns |
|---|---|
| `IslandModule.kt` | entry point, context capture, kill switch, wiring |
| `Geometry.kt` | all px arithmetic from the cutout |
| `IslandWindow.kt` | window add/remove, queue, TTL, animation, drives the fade |
| `IslandView.kt` | drawing + touch (tap opens, tap chip fires action, swipe up dismisses) |
| `IslandEvent.kt` | the one data type every source writes |
| `NotificationSource.kt` | `NotifCollection` hooks → events, group/count/row logic |
| `MediaSource.kt` | `MediaSessionManager` → the player capsule and the ambient ring |
| `ChargingSource.kt` | sticky `ACTION_BATTERY_CHANGED` → events |
| `PrivacySource.kt` | `PrivacyItemController.privacyList` → camera/mic/location dots |
| `AppOpsWatcher.kt` | `startWatchingActive` → the screen-recording capsule |
| `FaceUnlockSource.kt` | biometric success → one ring sweep |
| `CustomSource.kt` / `CustomReceiver.kt` | the automation door (Tasker / adb) |
| `GameSpace.kt` | the ColorOS Game Space session flag |
| `StatusBarFade.kt` | clock/icon alpha, and the `CommandQueue` fullscreen watcher |
| `HeadsUpSuppressor.kt` | ColorOS's own banner, per notification |
| `Config.kt` / `ConfigStore.kt` | the immutable settings snapshot and its `Settings.Global` channel |
| `SettingsActivity.kt` / `SettingsUi.kt` | the iOS-style settings screen, drawn not themed |
| `ModuleResources.kt` / `Logx.kt` | icon loading, and the log that answers "why did it drop" |

## Verification standard

A change is not done until it is seen on the phone: logcat line, screenshot, and measured bounds
compared against the cutout. Report what was actually run, and what could not be. `docs/`
carries the measurements behind every rule above.
