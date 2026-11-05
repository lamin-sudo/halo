# How Halo works

Everything here was measured on one device — an OPPO A79 5G (CPH2553, ColorOS 13.1, Android 13,
Magisk + LSPosed 2.2.1, libxposed API 102). Where a number appears it is the number that came off
that phone, not a guess, and where a route failed it is recorded as failed so nobody tries it twice.

## The shape of it

One overlay window, `TYPE_APPLICATION_OVERLAY` (2021), added inside `com.android.systemui` and
never anywhere else. One view draws everything — capsule, list card, player card, detail card,
call card, the ring around the hole — on a single `Canvas`. The window is attached while something
is showing and stays attached while a media session is playing (that is the ambient ring); it is
removed otherwise.

The module never hooks `system_server`, never installs an RRO, and never writes to
`/data/adb/lspd/config/modules_config.db`. Scope is declared statically and set once by the user.

## Entry point and packaging

The module is declared by `META-INF/xposed/` rather than by manifest meta-data:

```
java_init.list   io.github.lamin_sudo.halo.IslandModule
module.prop      minApiVersion=101 / targetApiVersion=102 / staticScope=true
scope.list       com.android.systemui
```

`IslandModule` implements `XposedModule` and does its work in `onPackageLoaded`. Because the entry
class is looked up *by name from a text file*, R8 has to keep it — `proguard-rules.pro` pins
`IslandModule` and `SettingsActivity` for exactly that reason, and the release dex is checked for
the class name before shipping.

## The one rule that matters: an interceptor must return the target's type on every path

```kotlin
module.hook(method).intercept { chain ->
    when (something) { ... }        // <-- last statement of the lambda
}
```

A `when` used as the tail expression of a lambda is a *statement* here, so the lambda evaluates to
`Unit`, and `Unit` handed back to a `Boolean` method inside SystemUI is a crash — twice, in this
project's history, the second one putting LSPosed into safe mode and silencing every module. Write
`return@intercept` explicitly on every branch. Same trap with a bare `try` as the tail.

Nothing may throw across a hook boundary either: every body is wrapped, and the failure mode this
module accepts is "no island", never "broken status bar".

## Hook map

| what it needs | target | why this one |
|---|---|---|
| every notification | `com.android.systemui.notification.NotifCollection#onNotificationPosted` | AOSP-named, sees everything before any OEM UI decision |
| the OEM heads-up card | `com.oplusos.systemui.notification.helper.HeadsUpHelper#shouldPeekByOS` + `SimpleHeadsUpController#show` | ColorOS decides the banner in two places; suppressing only one leaves the card |
| status-bar alpha | `CollapsedStatusBarFragment#onViewCreated` | the fragment root is the only container that holds clock *and* icons without naming OEM resource ids |
| privacy dots | `com.android.systemui.privacy.PrivacyItemController#updatePrivacyList` + field `privacyList` | SystemUI already computes it; reading is free, replacing their callback is not (see below) |
| fullscreen | `com.android.systemui.statusbar.CommandQueue#setTopAppHidesStatusBar(Z)V` | WindowManager's own verdict — see the section on it |

## Geometry

Nothing is hardcoded in dp. This device reports density 408 while the reference device reports
540, so dp copied from another build renders a pill shorter than the camera hole. `Geometry.kt` is
the only file doing arithmetic, and it derives from `WindowInsets.getDisplayCutout()` plus
panel-width fractions:

```
screen 1080x2400 · hole (540, 76x107) · pill height 127 px
idle width 388 px · active width 842 px · ring width ≈ hole + margin
```

The active pill spans x=189..891 while ColorOS's status icons start at x=642, which is why the
fade exists at all — the overlap is a physical fact, not a style choice.

## Notifications

`onNotificationPosted` gives a `StatusBarNotification`, and four filters run before anything is
shown, in this order:

1. **Group summaries are dropped** (`FLAG_GROUP_SUMMARY`) or the count double-takes.
2. **Ongoing rows are dropped** — except media, calls, and anything carrying real progress.
3. **Low importance is dropped** — except rows with progress, which are informational by design.
4. **Per-app gate** (`allow_<pkg>`).

Two ROM-specific discoveries live in here:

- `android.messages` is **not** a `MessagingStyle`; on this build it is a `Parcelable[]` of
  `Bundle`s with keys `sender_person`, `sender`, `text`, `time`. Reading
  `MessagingStyle.Message` off it always returned nothing.
- This ROM's `android.app.Person` has **no `getDisplayName()`** — `framework.jar` lists only
  `getName / getIcon / getIconUri / getKey / getUri / isBot / isImportant`. So the sender lookup
  tries `getDisplayName` reflectively and falls back to `getName`.
- `android.reduced.images=true` strips `EXTRA_LARGE_ICON` but **not** the Person icon inside the
  message bundle, which is how real sender photos still arrive.

Rapid bursts are grouped by slot key with a 3-second window, and the window dedupes re-delivery of
the *same* `sbn.key` — otherwise a single Telegram message that arrives through two entry points
counts as two.

## Media

Both a notification row and a live `MediaSession` describe the same song, and letting both write
the same slot blanks the progress line between updates. So the session wins as soon as it is live,
and the notification path reports `media handled by the session`. The session also gives artwork,
position and transport actions that a notification never carries.

While only the ring is up, a progress tick must not drag the capsule back on screen — the ring has
no line to move — so events carry a `refreshTtl` flag for exactly that distinction.

## The progress bar, and why 0% is not a download

`android.progress` in a notification's extras does **not** mean something is downloading. Measured
on this phone, four foreground-service rows carried a progress value with no scale at all:

```
com.truecaller        prog=0  max=0  flags=0x12
com.opencode.android  prog=0  max=0  flags=0x18 / 0x62
com.tailscale.ipn     prog=0  max=0  flags=0x60
```

So three conditions are required now: `progressmax > 0`, not indeterminate, and the row is an
active task (`isOngoing` or `CATEGORY_PROGRESS`). A real Play Store download passes on both counts
(`ongoing=true progress=0 category=status` at the first chunk). The log line prints
`progress=NN/max` so "why is there no bar" is one grep.

## Calls

The dialer posts a `CATEGORY_CALL` notification with **two** actions while ringing (answer,
reject) and **one** once the call is up. That difference is the whole state machine: two actions →
ringing card, one action → fold the card into a green ring. The slot key is `call|<pkg>` so the
answer transition replaces rather than stacks.

## Privacy dots and the reason we do not proxy their callback

`PrivacyItemController` holds a `privacyList` of items with a `PrivacyType` of camera, microphone
or location only — there is no projection type on this build. Their `addCallback(Callback)` keeps
its listeners in `WeakReference`s, so substituting a proxy for their list would silently drop the
OEM's own chip. Reading the field after `updatePrivacyList()` returns is free and cannot break
anything, so that is what happens.

Screen recording is caught separately through `AppOpsManager.startWatchingActive` on
`android:camera`, `android:record_audio`, `android:project_media`, rising edge only. ColorOS
records *without* a MediaProjection token, so `PROJECT_MEDIA` stays `ignore` forever and the
microphone op is the signal that actually fires. Consequence, stated in the README: a recording
with the microphone off is not detected.

## Fullscreen: four dead ends, then two real signals

This took the longest. Every route that looked *inside* SystemUI's view tree was blind:

| tried | result |
|---|---|
| layout listener on the status-bar root | never fires — ColorOS hides the bar by moving the window, which gives the view no layout pass |
| `StatusBarStateControllerImpl#setState(IZ)` | hook installed, **never called** — not by a lock, a wake, or forced immersive |
| the bar view's own state | reports `winVis=0 h=107 y=0 alpha=1` while `dumpsys window` says `ITYPE_STATUS_BAR: invisible` |
| our overlay's `statusBars` inset | **always 0** for a window with `FLAG_LAYOUT_IN_SCREEN` and a cutout. Trusting it silently dropped every notification for several minutes before the measurement caught it |

The answer was in the shipped dex instead. `CommandQueue` carries `MSG_SET_TOP_APP_HIDES_STATUS_BAR`,
and `CommandQueue#setTopAppHidesStatusBar(Z)V` is the concrete binder stub that `DisplayPolicy`
calls — the same fact `dumpsys window` was reporting, delivered to SystemUI by WindowManager:

```
23:09:30  window manager: top app hides status bar = true     ← YouTube fullscreen
23:09:31  standing down: app took the status bar
23:09:52  event dropped (fullscreen): custom|custom           ← fired while fullscreen
23:10:05  window manager: top app hides status bar = false    ← left fullscreen
23:10:12  show NOTIFICATION key=custom|custom
```

The safety check that let this default to on: sleep, wake, and a notification fired while the
keyguard was up produced **no** transition and the capsule still showed.

Games have a second, cheaper signal — ColorOS writes the Game Space session into
`Settings.Global/disturb_for_game_space_mode_flag`, readable with a `ContentObserver` and no hook
at all:

```
focus=com.oplus.games          flag=0    ← launcher, not a game
focus=com.supercell.brawlstars flag=2    ← game session
focus=com.tencent.ig           flag=2    ← PUBG, notification block off
focus=com.tencent.ig           flag=3    ← block switched on mid-match
focus=com.qoder.mobile         flag=0    ← back out, clears by itself
```

The companion `..._mode` key is deliberately unused: it reads 1 outside a game and 0 inside one,
which is the opposite of what its name suggests.

And the finding that reframed the whole feature: **Game Space's "reject notifications" is a UI
filter, not a pipeline filter.** With `flag=3` live and PUBG in focus, a real notification still
reached `NotifCollection` and still rendered. OEM do-not-disturb cannot silence this module by
accident — which is why the module obeys it deliberately instead.

## Configuration channel

Settings live in `Settings.Global` under `a79_island_*`, not in libxposed remote preferences: the
remote-prefs path returns an empty key set on this LSPosed build, which is how one switch sat
switched-on in the UI while the hook kept doing the opposite. A `ContentObserver` on
`Settings.Global.CONTENT_URI` makes every change live without restarting SystemUI, and the config
is reloaded into one immutable snapshot so the hot path reads fields instead of querying settings
per event. Writes need `WRITE_SECURE_SETTINGS`, granted once from a PC.

Reloads that change nothing stay silent — this ROM writes global settings every few seconds, and
without a `sameAs()` comparison the log fills with identical lines.

## The automation door

`CustomReceiver` is exported with no permission on purpose (the point is that another process can
reach it, and the worst it can do is put a pill on screen). It runs in the *app* process, which is
the half that holds `WRITE_SECURE_SETTINGS`, and writes the payload plus a sequence number into
`Settings.Global`; the SystemUI half already watches that table, so no second channel exists.
`CustomSource` seeds its last-seen sequence from the stored value, which means a request made
before SystemUI restarted is never replayed and one made a second after it starts is never
dropped.

Two gotchas that cost real time: an implicit broadcast to a custom action is filtered since
Android 8 unless the package is named (`-p` or `-n`), and `Broadcast completed: result=0` proves
nothing — check that the sequence number moved. Quoted extras inside `su -c "..."` also get split
by the shell; keep values free of spaces or use `-n`.

## Diagnostics that answer questions

```bash
adb logcat -s HaloIsland:D                       # every decision, with the reason it dropped
adb shell su -c 'lspctl status'                 # is the module even loaded / safe mode?
adb shell su -c 'tail -f /data/adb/lspd/log/modules_*.log'
adb shell dumpsys notification --noredact       # the real extras of any row
adb shell dumpsys window | grep mCurrentFocus
adb shell settings get global disturb_for_game_space_mode_flag
```

When the island goes quiet, `lspctl status` first: a safe-mode event in this project's history was
caused by two *other* modules crashing (35 entries for one of them, zero lines containing `island`).

## Still unknown

- Battery cost of the ambient ring (the soak script exists, the number does not).
- Behaviour on non-ColorOS Android 13 ROMs — the AOSP-named hooks should hold, the heads-up gate
  and geometry will not.
- Whether any app legitimately needs the progress bar without declaring `isOngoing` or
  `CATEGORY_PROGRESS`. The gate would drop it; no such app has been found yet.
