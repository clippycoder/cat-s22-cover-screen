# CAT S22 Flip — External Cover Screen: Reverse-Engineered API Reference

**Device:** CAT S22 FLIP (serial redacted), Android 11 (API 30), Qualcomm/Borqs/Mmax build
**Scope:** Everything needed to programmatically access, inject content into, or build apps for the external 128×128 cover display.
**Status:** All broadcast APIs in §5 were **verified live on-device** via adb (see §9).
**Reverse-engineered from:** `/system/system_ext/priv-app/SystemUI/SystemUI.apk` (decompiled in this directory — see §10).

---

## 1. Hardware & display properties

The cover screen is a **real, public secondary display** — not a hack or a virtual display.

| Property | Value |
|---|---|
| Display ID | `1` |
| uniqueId | `local:1` |
| Name (DisplayDeviceInfo) | `"HDMI Screen"` |
| Resolution | 128 × 128 px |
| Refresh rate | 30 Hz (mode id 2, the only mode) |
| Type | `EXTERNAL` (physical port 1) |
| Flags | `FLAG_SECURE`, `FLAG_SUPPORTS_PROTECTED_BUFFERS`, `FLAG_PRESENTATION`, `FLAG_TRUSTED` |
| Density | 213 dpi (`sw96dp` → 96 dp wide) |
| Privacy | **Public** — no `FLAG_PRIVATE`, so any app may render to it |
| Layer stack | 1 |

Internal display: `displayId=0`, "Built-in Screen", 480×640 @ 60 Hz.

Inspect at any time:

```bash
adb shell dumpsys display | grep -A3 "HDMI Screen"
adb exec-out screencap -d 1 -p > cover.png     # screenshot the cover screen (works!)
```

---

## 2. Architecture overview

```
Hall sensor (kernel, /dev/input/hall_dev, SW_LID switch)
        │
        ▼
Vendor framework (NOT in SystemUI.apk — lives in system_server / HAL)
        │
        ├─► sticky broadcast  android.os.action.HALL_COVER_CHANGED  (extra "cover": boolean)
        │       ├─► FlipCoveredScreenActivity   → finish() when cover=false
        │       └─► PresentationScreen          → updates mIsHallCover, rebuilds UI
        │
        ├─► vendor PowerManager API  PowerManager.getIsHallCover()Z   (pollable ground truth)
        │
        ├─► broadcast  android.os.action.VOLUME_PRESS  (extra "pressmode": int) — external side keys
        │
        └─► system service  "sublcd_present"  (com.mmax.common.ISubPresentService)
                ├─ persists PTT/cover-screen state across Presentation recreations
                └─ pushes key events to SystemUI via ISubPresentCbInterface.myDispatchKeyEvent()

When the flip CLOSES:
  1. Framework turns internal display (id 0) OFF.
  2. Framework starts com.android.systemui/.keyguard.FlipCoveredScreenActivity
     on display 1 (exported=true; extra "delayMs": int, default 800) — a plain black
     placeholder window that "owns" the display.
  3. SystemUI StatusBar.showPresentationScreen() shows the real UI on top:
     a PresentationScreen (android.app.Presentation) with clock, icons, banners, etc.
  4. On flip OPEN: framework turns display 0 back ON; FlipCoveredScreenActivity and
     PresentationScreen tear themselves down (PresentationScreen recreates itself
     via a stop-listener loop, so it survives display on/off cycles).
```

**Key consequence:** everything user-visible on the cover screen is drawn by
`PresentationScreen`, which registers **unprotected, dynamically-registered broadcast
receivers**. Any app (no permissions) or adb shell can drive it. Those receivers are
the de-facto public API of the cover screen.

**Lifetime (verified against AOSP Presentation semantics + decompile + live testing):**
the `PresentationScreen` instance **survives flip open/close and cover-screen power
cycles** — `android.app.Presentation` only cancels on display *removal* or a metrics
change, neither of which a power cycle causes. Its view state (icon visibility, banner)
persists across those transitions, and its receivers are therefore effectively always
registered while SystemUI runs. Broadcasts sent while the flip is open simply set view
state on the sleeping cover display; `wakeUp()` is internally gated on the screen being
off. No flip-gating is needed in client code.

---

## 3. SystemUI components (decompiled)

All paths relative to `jadx-out/sources/` in this directory.

| Class | Role |
|---|---|
| `com/android/systemui/presentationscreen/PresentationScreen.java` (2395 lines) | The cover-screen UI. An `android.app.Presentation`. Monolith: views, 7+ broadcast receivers, telephony/SMS/MMS/contacts queries, volume logic, rear-camera viewfinder + capture, PTT panels, wakelock management. |
| `com/android/systemui/presentationscreen/BatteryView.java` | Custom vertical battery bar view (appears unused/legacy — the layout uses `BatteryMeterView` instead). |
| `com/android/systemui/keyguard/FlipCoveredScreenActivity.java` | Black fullscreen placeholder activity on display 1. Self-kills on `HALL_COVER_CHANGED (cover=false)` or delayed `PowerManager.getIsHallCover()` poll (`delayMs` extra). Renders nothing itself. |
| `com/android/systemui/statusbar/phone/StatusBar.java` | `showPresentationScreen()` at line 3676 — creates/shows the Presentation. Finds the display via `MediaRouter.getSelectedRoute(LIVE_VIDEO).getPresentationDisplay()`, **never hardcodes displayId 1**. Called from `start()` (line 794) and self-recreates via `PresentationStopListener`. |
| `com/android/systemui/statusbar/policy/ClockSubLcd.java` | The cover-screen clock TextView (time-tick driven). |

Vendor classes referenced but **not** in the APK (they live in the framework):
`com.mmax.common.SubPresentManager`, `com.mmax.common.ISubPresentCbInterface`,
`com.mmax.common.ISubPresentService` (system service name: `"sublcd_present"`,
confirmed via `adb shell service list`, entry #152).

Layout: `apktool-out/res/layout/presentation_content.xml` (the 128×128 UI),
`apktool-out/res/layout/flip_covered_screen_layout.xml` (empty black LinearLayout).

---

## 4. Flip state

Two ways to read it:

1. **Broadcast** (push): `android.os.action.HALL_COVER_CHANGED`, sticky, boolean extra
   `"cover"` — `true` = flip closed / covered. Sent by the framework; SystemUI only consumes.
2. **Vendor API** (poll): `PowerManager.getIsHallCover()` — a Mmax/Borqs extension to
   `android.os.PowerManager`, not in the public SDK. Callable from an app compiled
   against this device's framework (or via reflection; requires no permission, but is
   hidden API — on Android 11 hidden-API access may need `--no-hidden-api-checks` /
   debuggable builds).

Raw hardware: `getevent` on the hall device shows `SW_LID` switch events
(`adb shell getevent -pl` lists it as `hall_sensor`; the `/dev/input/hall_dev` node
name seen in dumpsys may differ from the accessible node).

---

## 5. The broadcast API (the important part)

All receivers below are registered by `PresentationScreen.onCreate()` and, per the
lifetime finding above, stay registered across flip and power transitions (i.e.,
**callable at any time**). They are dynamic receivers with no permission requirement →
callable from **any app** and from **adb shell**. When sending from adb, target SystemUI
explicitly (`-p com.android.systemui`) — Android 11 may not deliver untargeted implicit
broadcasts reliably.

### 5.1 `ptt.sublcd.action.NOTIFY` — render ARBITRARY content
> Handler: `PresentationScreen.java:405-420`

| Extra | Type | Meaning |
|---|---|---|
| `_id` | int | Arbitrary request id (logged only) |
| `_notification` | `Notification` (parcelable) | **Required.** Its `contentView` (RemoteViews) is inflated into the cover screen's `ptt_remote_info` view group. |

This is the most powerful hook: a `RemoteViews` supports `TextView`, `ImageView`,
`LinearLayout`/`RelativeLayout`/`FrameLayout`, `Chronometer`, `AnalogClock`,
`ProgressBar`, and `setOnClickPendingIntent`. You can draw essentially arbitrary
layouts on the 128×128 screen. Replaces whatever PTT content was showing.

Behavior details (verified):
- The panel is a **full-area white-backed vertical LinearLayout** occupying everything
  below the 16dp status strip; the clock/banner container (`normal_info_screen`) is
  hidden while it is up; the status strip stays visible.
- **It does NOT wake the screen** — pair with `notification.change isShow=true`
  (or `WAKEUP_SEC`) if the display may be asleep.
- Panel state is mirrored into the `sublcd_present` service (`saveShowPtt`), so it
  survives Presentation recreation — **always pair with CANCEL/CANCEL_ALL**, or the
  panel reappears on the next flip close after a crash.
- `CANCEL` and `CANCEL_ALL` are functionally identical for third-party use.
- With `mPttState=1` (NOTIFY), external volume keys are NOT captured for list
  navigation (that only happens with `NOTIFY_LIST`, which sets `mPttState=2`).
- Requires an app (parcelables can't be attached via `am broadcast`).

Three things the handler and layout make non-obvious (`PresentationScreen.java:413-419`,
`refreshView()` at `:1648`, `res/layout/presentation_content.xml:79-80`):

* **The stock clock goes away.** While a panel is up, `normal_info_screen` is GONE and only
  `status_bar_contents` (16 dp) stays visible. A panel meant to persist has to draw its own
  time — `TextClock` is remotable, so it can tick without further broadcasts.
* **The container is white.** `ptt_remote_info` has `@android:color/white` as its background,
  so a RemoteViews root that does not paint its own background flashes white.
* **It does not wake the screen** — see caveat 5 below.

### 5.2 `ptt.sublcd.action.NOTIFY_LIST` — text list screen ✅ verified
> Handler: `PresentationScreen.java:421-437`

| Extra | Type | Meaning |
|---|---|---|
| `_title` | String | Title line |
| `_list` | StringArrayList | List rows (rendered by `PttHistoryListAdapter`) |
| `_id` | int | Request id (logged only) |

Replaces the whole cover screen with title + scrollable list. External VOL+/- keys
navigate the list (`handleKeyDown`, line 2250). Persists until cancelled.

```bash
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.NOTIFY_LIST \
  --es _title "MY TITLE" --esa _list "row 1,row 2,row 3"
```

### 5.3 `ptt.sublcd.action.NOTIFY_ICON` — status icon
> Handler: `PresentationScreen.java:477-495`

| Extra | Type | Meaning |
|---|---|---|
| `_id` | int | Request id |
| `_notification` | `Notification` | If extras contain a Bitmap under key `"icon_white"`, it's shown as-is. Otherwise the notification's `smallIcon` is tinted **inverse** (ColorMatrix −1 +255 per channel) and shown. |

Shows a small icon in the cover-screen status area until cancelled. Requires an app.

**There is exactly one slot.** The handler drives a single `ImageView` (`mPttIcon`,
`PresentationScreen.java:170`; `presentation_content.xml:5`), so icons do not stack — each send
replaces the previous one, last writer wins. Per-app icon strips are not possible through this
broadcast; draw them inside a `NOTIFY` panel instead.

### 5.4 `ptt.sublcd.action.CANCEL` / `ptt.sublcd.action.CANCEL_ALL` — restore ✅ verified
> Handler: `PresentationScreen.java:439-459`

No required extras (`_id` logged only). Hides all PTT panels, clears persisted PTT
state (`SubPresentManager.saveShowPtt(false)`), restores the normal clock view.

```bash
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.CANCEL_ALL
```

### 5.5 `ptt.sublcd.action.CANCEL_ICON` — hide status icon
> Handler: `PresentationScreen.java:495-497`. No required extras.

### 5.6 `ptt.sublcd.action.WAKEUP_SEC` — keep screen awake
> Handler: `PresentationScreen.java:461-476`

| Extra | Type | Meaning |
|---|---|---|
| `_isOn` | boolean | `true` → acquire vendor wakelock `"ptt-bright"` (keeps cover screen lit); `false` → release. |

Only acquires while flip is closed (`mIsHallCover`). Requires an app? No — boolean
extras work from adb: `--ez _isOn true`. **Remember to release**, or the cover LCD
stays on indefinitely.

### 5.7 `android.intent.action.notification.change` — SMS/missed-call/voicemail/alarm icons ✅ verified
> Handler: `PresentationScreen.java:279-370` (main receiver)

| Extra | Type | Meaning |
|---|---|---|
| `pkg` | String | Which indicator to drive (see table) |
| `isShow` | boolean | Show (`true`) or hide (`false`) |

| `pkg` value | Effect on `isShow=true` |
|---|---|
| `com.android.mms` or `com.android.messaging` | SMS envelope icon + banner with the **latest cellular SMS/MMS sender** (queried live from `Telephony.Sms`/`Telephony.Mms` + ContactsContract). Wakes the screen. Banner auto-hides after **8 s** (`refreshViewDelayed(8000)`); icon stays until `isShow=false`. Suppressed while a call is ringing. **Known limitation:** the banner text comes from the cellular SMS DB only — messages arriving via RCS or Google Voice over data never land there, so the banner will show a stale/earlier sender for those. The envelope icon itself is unaffected. Apps needing correct names for non-SMS messages should overlay their own `NOTIFY` panel (§5.1). |
| `com.android.server.telecom` or `org.codeaurora.dialer` | Missed-call icon |
| `org.codeaurora.dialer.voicemail` | Voicemail icon |
| `com.android.deskclock` | Alarm icon (count-based) |
| `com.android.dialer` | `isShow=false` clears the caller banner |

Verified live:
```bash
adb shell am broadcast -p com.android.systemui \
  -a android.intent.action.notification.change \
  --es pkg com.android.mms --ez isShow true     # envelope icon + sender name
# ... and --ez isShow false to clear the icon
```

### 5.8 Other consumed broadcasts (read-only context)

| Action | Extra(s) | Effect |
|---|---|---|
| `android.os.action.HALL_COVER_CHANGED` | `cover`:bool | Updates internal state; on open: clears volume UI, exits cover-camera mode and launches `com.borqs.camera` |
| `android.os.action.VOLUME_PRESS` | `pressmode`:int | External side keys. `1`=VOL+ (exits camera mode if active), `2`=VOL− (shutter in camera mode), `3`=volume change (blocked if `external_keys_lock_control` set), `4`=locked-key signal. Always wakes the screen first. |
| `com.android.deskclock.ALARM_ALERT` / `ALARM_DONE` | — | Alarm panel show/hide |
| `com.android.presentationScreen.vowifi.show` / `.hide` | — | VoWiFi icon |
| `android.intent.action.SCREEN_ON/OFF`, `HEADSET_PLUG`, `WALLPAPER_CHANGED`, `LOCALE_CHANGED`, `SIM_STATE_CHANGED`, `android.media.VOLUME_CHANGED_ACTION`, ringer/zen/interruption changes | — | Standard UI refresh |

### 5.9 Settings keys read by the cover screen

| Key | Table | Meaning |
|---|---|---|
| `secondary_screen_charging_bright` | Settings.System | Keep cover screen bright while charging |
| `external_keys_lock_control` | Settings.Secure | Lock the external volume keys |
| `ptt_sublcd_hist_index` | Settings.Global | PTT history list position |

---

## 6. Drawing your OWN full-screen UI: the Presentation API

Because display 1 is **public** (no `FLAG_PRIVATE`), any ordinary app can bypass the
broadcast hooks entirely and render its own UI, exactly like SystemUI does:

```java
DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
for (Display d : dm.getDisplays()) {
    if (d.getDisplayId() != 0 && (d.getFlags() & Display.FLAG_PRESENTATION) != 0) {
        Presentation p = new MyPresentation(context, d); // 128x128 canvas, design for 96dp
        p.show();
    }
}
```

Notes:
- Use `MediaRouter.getSelectedRoute(ROUTE_TYPE_LIVE_VIDEO).getPresentationDisplay()`
  like SystemUI does, or just pick any non-default display with `FLAG_PRESENTATION`.
- Catch `WindowManager.InvalidDisplayException` from `show()`.
- The display is only powered while the flip is closed; register a
  `DisplayListener` (`onDisplayAdded/Removed`) to show/hide.
- **Coexistence:** SystemUI's `PresentationScreen` window and yours will both target
  display 1 (z-order fight). For *adding content* (icons/banners), prefer the
  broadcast API in §5 — it cooperates with SystemUI instead of fighting it.
- Shell `am start --display 1` does **NOT** work for launching activities (verified —
  the activity lands on display 0). Activities on display 1 require framework
  privileges (`setLaunchDisplayId` ActivityOptions from a privileged caller).
  `Presentation` dialogs are the supported app-side path.

---

## 7. Recipes (adb, all verified)

```bash
# Screenshot the cover screen
adb exec-out screencap -d 1 -p > cover.png

# Show a text list on the cover screen
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.NOTIFY_LIST \
  --es _title "TITLE" --esa _list "one,two,three"

# Restore the normal clock
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.CANCEL_ALL

# Show / clear the SMS envelope icon + latest-sender banner
adb shell am broadcast -p com.android.systemui \
  -a android.intent.action.notification.change \
  --es pkg com.android.mms --ez isShow true     # / false to clear

# Keep the cover screen awake (release when done!)
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.WAKEUP_SEC --ez _isOn true
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.WAKEUP_SEC --ez _isOn false

# Watch the cover-screen log (tag: PresentationScreen, debug-gated lines need isDdebug)
adb logcat -s PresentationScreen

# Flip state via input subsystem
adb shell getevent -pl | grep -A3 hall
```

---

## 8. Caveats & gotchas

1. **Flip state matters less than assumed.** The receivers are alive across flip and
   power transitions (§2), so broadcasts can be sent at any time; SystemUI gates the
   visible effects internally (`wakeUp()` only fires when the screen is off). Announces
   made while the flip is open land as view state and are already correct at the next
   flip close. `WAKEUP_SEC` additionally checks `mIsHallCover` and only holds while covered.
2. **Target SystemUI explicitly** from adb (`-p com.android.systemui`); untargeted
   implicit broadcasts were observed not to fire the receiver on this build.
3. **8-second banner timeout:** the SMS banner (`notification.change`) auto-hides
   after 8 s; the icon persists. PTT panels (`ptt.sublcd.*`) persist until cancelled.
4. **State persistence:** PTT show/state is mirrored into the `sublcd_present` service
   (`saveShowPtt`, `savePttState`), but **SystemUI never reads it back**: `mShow_Ptt` is
   initialised to `false` in the constructor (`PresentationScreen.java:258`) and is only ever
   assigned by the four PTT handlers. So a recreated Presentation comes up on the stock clock —
   panels and status icons are *lost* on recreation, not resurrected. Anything that must survive
   a cover-screen power cycle has to be re-sent by the app.
5. **Screen wake on events:** `wakeUp()` (an 8 s FULL_WAKE_LOCK, `PresentationScreen.java:1182`)
   has exactly two callers: the SMS branch of `notification.change` (`:339`) and `VOLUME_PRESS`
   (`:608`). **`NOTIFY` and `NOTIFY_LIST` do NOT wake the screen** — they only call
   `refreshView()`, which either holds the screen on while charging (if
   `secondary_screen_charging_bright` is set) or calls `resetScreenOn()`. To light the screen for
   injected content, send `notification.change`/`isShow=true` first, then the panel.
   *(Earlier revisions of this document claimed NOTIFY wakes the screen. It does not.)*
6. **No permissions** are required for any §5 broadcast — they are also open to
   *other* apps, so treat them as untrusted input if you build on them.
7. **`FLAG_SECURE`** on display 1: screenshots via `screencap -d 1` still worked from
   shell (shell has READ_FRAME_BUFFER), but unprivileged screen capture of the
   display content is blocked.
8. **RemoteViews limits** apply to `ptt.sublcd.action.NOTIFY` content (standard
   layout widgets only; no custom `View` subclasses).
9. **Hidden APIs:** `PowerManager.getIsHallCover()` and `com.mmax.common.*` are not
   in the public SDK. Reflection may hit Android 11's hidden-API restrictions unless
   the app is system/priv-app or uses supported workarounds.

---

## 9. Verification log (on-device, 2026-08-24)

| Test | Result |
|---|---|
| `screencap -d 1` | ✅ 128×128 RGBA PNG captured |
| `NOTIFY_LIST` injection | ✅ Logcat: `handle action, notify list, id:0, title:RE`; screenshot shows injected title replacing clock (`display1_ptt_test.png`) |
| `CANCEL_ALL` | ✅ Clock view restored (`display1_restored.png`) |
| `notification.change` (`pkg=com.android.mms`, `isShow=true`) | ✅ SMS envelope icon + latest sender banner shown (`display1_sms_icon.png`); cleared with `isShow=false` |
| Untargeted (no `-p`) broadcast | ❌ Receiver did not fire — always use `-p com.android.systemui` |
| `am start --display 1` | ❌ Activity launched on display 0 anyway — shell cannot route activities to display 1 |
| `service list` contains `sublcd_present` | ✅ Entry #152: `sublcd_present: [com.mmax.common.ISubPresentService]` |
| Hall sensor present | ✅ `getevent -pl`: `hall_sensor`, `SW_LID` switch |
| Presentation lifetime | ✅ View state (icon/panel) persists across flip open/close and cover-screen sleep/wake; receivers always registered (§2 lifetime finding) |
| `WAKEUP_SEC _isOn=true` from full sleep | ✅ Wakes the cover screen (wakelock flags `0x1000000A` include `ACQUIRE_CAUSES_WAKEUP`); release when done |
| `NOTIFY` panel (RemoteViews, via app) | ✅ Full-area panel replaces clock/banner; status strip stays; `CANCEL` restores clock; stale-banner race normalized by SystemUI's pending 8s handler |
| Banner sender for RCS/data messages | ❌ Shows stale cellular-SMS sender (§5.7 limitation) — worked around in Cover Notifier app by overlaying a NOTIFY panel with the correct name |
| `NOTIFY_LIST` while **both displays report `state OFF`** (2026-08-24, audit) | ✅ Handler fired: `handle action, notify list, id:0, title:AUDIT`. Receivers are alive with the cover display powered down — cover-screen power state is NOT a gate. |
| Receivers while the flip is **open** | ❓ Still untested. `onStop()` (`:1017-1050`) unregisters every receiver and `StatusBar.showPresentationScreen()` (`:3676`) only rebuilds when MediaRouter still yields a presentation display, so this is the one gate that may be real. |

---

## 10. Reproducing the decompile

None of the vendor material is in this repository - no `SystemUI.apk`, no decompiler output, no
cover-screen screenshots. Everything below is reproducible from a device you own:

```bash
adb pull /system/system_ext/priv-app/SystemUI/SystemUI.apk
jadx -d jadx-out SystemUI.apk          # java sources; line numbers here refer to this output
apktool d -o apktool-out SystemUI.apk  # resources: res/layout/presentation_content.xml
```

Start at `com/android/systemui/presentationscreen/PresentationScreen.java`. Line numbers cited in
this document come from jadx and will drift with a different jadx version or ROM build.

### Key line references (jadx sources)

| What | Where |
|---|---|
| Main receiver (all §5.7/§5.1-5.6 actions) | `presentationscreen/PresentationScreen.java:279-500` |
| Hall/volume receiver | `presentationscreen/PresentationScreen.java:570-643` |
| Receiver registration | `presentationscreen/PresentationScreen.java:750-797` |
| `sublcd_present` handle + callback registration | `presentationscreen/PresentationScreen.java:826-828`, `2294-2305` |
| Wake / keep-screen-on | `presentationscreen/PresentationScreen.java:1182-1189`, `2034-2061` |
| Cover camera (open/shoot/save) | `presentationscreen/PresentationScreen.java:1751-2029` |
| SMS/MMS/contact queries | `presentationscreen/PresentationScreen.java:1192-1298` |
| Presentation creation | `statusbar/phone/StatusBar.java:3676-3708` |
| FlipCoveredScreenActivity self-teardown | `keyguard/FlipCoveredScreenActivity.java:18-68` |

---

## 11. Open questions / not yet verified

- Whether `PowerManager.getIsHallCover()` is reachable via reflection from a
  normal (non-priv) app on this build (hidden-API blocklist status untested).
- Exact semantics of `SubPresentManager.savePttState(int)` values beyond what
  SystemUI writes (`1` = remote info screen, `2` = history list, `0` = none).
- Whether `am broadcast` of `ptt.sublcd.action.NOTIFY` with a Notification built by
  an app is subject to any sender checks (code shows none).
- **Whether the receivers stay registered while the flip is open** (see §9). Cheapest test: open
  the flip, send a `NOTIFY_LIST`, and grep logcat for `handle action`.
- Behavior when multiple apps race PTT content (last-writer-wins expected).
