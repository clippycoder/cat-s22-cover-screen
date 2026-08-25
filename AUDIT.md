# Cover Notifier — code audit

**Audited:** `app-release.apk` (versionCode 2 / 0.2, built 2026-08-24 20:45) and the sources it was
built from — verified no `.kt` is newer than the APK, so binary and tree agree.
**Method:** read every source file, cross-checked every claim about SystemUI against the jadx
decompile in `a local jadx decompile of SystemUI.apk`, and ran one live adb probe against the device.

## What the app actually is

688 lines of Kotlin, four files. A `NotificationListenerService` watches three SMS packages and,
per new message, runs: `notification.change isShow=true` (wakes the cover screen, shows SystemUI's
envelope icon and its own banner) → +300 ms `ptt.sublcd.action.NOTIFY` with a one-line panel
carrying the correct sender → +8.3 s `ptt.sublcd.action.CANCEL`. No foreground service, no
calendar, no per-app icons, no permissions declared at all.

## Verified against the decompile

| Claim in the code | Verdict |
|---|---|
| `NOTIFY` does **not** wake the screen | **Correct.** `wakeUp()` is called only at `PresentationScreen.java:339` (the SMS `notification.change` path) and `:608` (volume press). `docs/EXTERNAL_SCREEN_API.md` §8.5 says otherwise and is wrong. |
| Panel replaces the clock area, status strip stays | **Correct.** `refreshView()` (`:1648`) sets `normal_info_screen` GONE and `status_bar_contents` VISIBLE when `mShow_Ptt`. |
| Receivers survive cover-screen power-off | **Correct, and I confirmed it live:** with both displays reporting `state OFF`, a `NOTIFY_LIST` broadcast still logged `handle action, notify list, id:0, title:AUDIT`. |
| Receivers survive **flip open** | **Unverified — see F1.** |

Also settled, so `DESIGN.md` risk #4 can be closed: SystemUI never restores PTT state. `mShow_Ptt`
is set to `false` in the constructor (`:258`) and is never read back from `sublcd_present`. Panels
are *lost* on Presentation recreation, not resurrected. There is no stale-white-panel failure mode.

---

## Findings

### F1 — HIGH — "no flip-gating anywhere" is an unverified assumption, and the decompile leans against it
`CoverScreen.kt:15`, `MessageCollector.kt:31-37`

Both doc comments state the Presentation and its receivers survive flip open/close, attributing this
to the decompiled lifecycle. The decompile shows the opposite risk: `onStop()` (`:1017-1050`)
unregisters *every* receiver, and `StatusBar.showPresentationScreen()` (`:3676`) only rebuilds when
`MediaRouter.getSelectedRoute(LIVE_VIDEO).getPresentationDisplay()` is non-null. If opening the flip
removes display 1 from that route, there is a window with no receiver and no rebuild.

What I could prove is narrower: receivers are alive while the cover display is **powered off**. That
kills the old "flip closed only" assumption but says nothing about flip *open*.

If the assumption is wrong, every announce that lands while the phone is open is dropped, and because
nothing in the app watches flip state, nothing redraws on close — the user closes the flip to a stock
clock and never sees the message.

Decisive test, ~30 s, no install needed:
```bash
adb logcat -c
# open the flip, leave it open
adb shell am broadcast -p com.android.systemui \
  -a ptt.sublcd.action.NOTIFY_LIST --es _title "OPEN" --esa _list "probe"
adb logcat -d -s PresentationScreen StatusBar | grep -E "handle action|onStop|Showing presentation"
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.CANCEL_ALL
```
`handle action, notify list` present → the comments are right, delete this finding. Absent, or
preceded by `onStop` → re-add a flip monitor and re-announce on `cover=true`. `FlipMonitor.kt` is
already written and currently unused (F9).

### F2 — HIGH — envelope icon disappears for good after a cover-screen power cycle
`MessageCollector.kt:160` (`syncIcon`), `:50` (`iconShown`)

A recreated `PresentationScreen` starts with every status icon hidden and `mShow_Ptt = false`
(`:258`); nothing is restored. The app caches what it last sent in `iconShown` and returns early when
it matches, so after any Presentation recreation with unread messages still pending, the icon is gone
from the screen while the app believes it is showing. It stays gone until the *next* new message.

Fix: treat cover-screen state as unknown whenever the Presentation may have been rebuilt — listen for
`ACTION_SCREEN_ON` and null out `iconShown` (plus `panelUp`) before the next sync, or simply re-assert
the icon instead of deduping it (it is one cheap broadcast).

### F3 — MEDIUM-HIGH — the app clears SystemUI's own SMS icon, including at startup
`MessageCollector.kt:160-172`

`clear()` sends `notification.change pkg=com.android.messaging isShow=false`, which hides the
*native* envelope — the same indicator the stock path raises for a genuine unread SMS. The app's
filter is narrower than SystemUI's, so the two disagree routinely.

Worst case is on the very first connect with no unread: `iconShown` is `null`, `null == false` is
false, so `syncIcon(false)` fires and clears an icon the app never raised. Every boot and every
listener rebind can wipe a real unread indicator.

Fix: only ever send `isShow=false` when this process sent `isShow=true` (`if (iconShown != true) return`).

### F4 — MEDIUM — a panel can be stranded on screen across process death
`MessageCollector.kt:52`, `:164-172`

`panelUp` is per-process. If the listener process dies between `showPanel` and `hidePanel`, the panel
persists (PTT content survives until cancelled). On reconnect with no unread, `clear()` skips
`cancelPanel()` because `panelUp` is false, so a stale sender sits on the cover screen until a screen
cycle drops it.

Fix: unconditional `CoverScreen.cancelPanel(this)` in `onListenerConnected()` before `rebuild()`.

### F5 — MEDIUM — app picker cannot see most apps (targetSdk 30 package visibility)
`MainActivity.kt:155`, `app/src/main/AndroidManifest.xml`

`queryIntentActivities(SENDTO smsto:)` and `getApplicationInfo()` are filtered on targetSdk 30 unless
the manifest declares `<queries>` or `QUERY_ALL_PACKAGES`. The manifest declares no permissions and no
`<queries>` block (confirmed: `aapt2 dump badging` lists zero `uses-permission`). The picker can only
ever surface the hardcoded defaults plus the default SMS app.

Fix, whichever matches intent:
```xml
<queries>
    <intent><action android:name="android.intent.action.SENDTO"/><data android:scheme="smsto"/></intent>
</queries>
```
or `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES"/>` if the picker is meant to
list non-SMS messengers later.

### F6 — MEDIUM — dedup key can collide
`MessageCollector.kt:113`

`newKey = postTime shl 4 or id.toLong()` assumes notification ids fit in 4 bits. Ids above 15 overlap
the shifted timestamp, so distinct notifications can produce equal keys (announce suppressed) and
equal notifications distinct keys (announce repeated). Use `postTime` combined with `sbn.key.hashCode()`,
or a `Pair`.

### F7 — LOW-MEDIUM — `FLAG_LOCAL_ONLY` is not a "not a message" signal
`MessageCollector.kt:225`

`FLAG_LOCAL_ONLY` means "do not bridge this to other devices"; some messaging apps set it on perfectly
real messages. Filtering on it silently drops those. The ongoing/summary/foreground-service flags in
the same expression are correct; this one should go.

### F8 — LOW — the preview text is parsed and thrown away
`MessageCollector.kt:47`, `:122`, `buildPanel()`

`newestText` is stored but never rendered — the panel shows sender only, while the class doc advertises
"correct sender/preview". Either render it (the layout has room for a second line) or drop the field and
fix the comment.

### F9 — LOW — dead code and dead directories
- `flip/FlipMonitor.kt` — 84 lines, zero references anywhere in the tree.
- `CoverScreen.sendList()` / `keepLit()` — unused (harmless as API surface; `keepLit` unused is *good*,
  it is the one that can leave the LCD lit forever).
- Empty `app/src/main/java/com/covernotifier/render/` and `app/src/main/res/xml/`.

Keep `FlipMonitor` only if F1 resolves against the current assumption; otherwise delete it.

### F10 — LOW — stray second manifest, wrong package, references classes that do not exist
`app/AndroidManifest.xml`

Declares a package name unrelated to the real one, `minSdkVersion 21`, and components `SetupActivity` /
`MessageRelayListener` that are not in the tree. AGP builds from `app/src/main/AndroidManifest.xml`
and ignores this file, so it is inert — but it is the first manifest a reader opens. Delete it.

### F11 — LOW — the two docs no longer describe this app
`DESIGN.md` specifies a foreground service, a calendar poller, per-app status-strip icons and a
persistent panel. None of that exists; the shipped app is an 8-second SMS banner replacement. Either
mark the design superseded or fold the delta into it.

`docs/EXTERNAL_SCREEN_API.md` needs three corrections the decompile settles:
- §8.5: `NOTIFY`/`NOTIFY_LIST` do **not** call `wakeUp()` (only `notification.change` SMS and volume press do).
- §5.3: `NOTIFY_ICON` drives a **single** `ImageView` (`mPttIcon`, `:170`), not a strip — icons do not stack, each send replaces the last.
- §5.1: the panel container `ptt_remote_info` has a **white** background and, while shown, `normal_info_screen` is GONE — the stock clock is hidden, so a persistent panel must draw its own time.

### F12 — INFO — packaging and repo
- Release APK is signed with the **debug** key (`app/build.gradle.kts`, `signingConfig = signingConfigs.getByName("debug")`);
  verified: `CN=Android Debug`. Fine for sideloading, but a later switch to a real key means uninstall-first.
- The git repo has **no commits** — everything is untracked, including this audit.
- Installation is blocked at the policy level, not by the APK: the device has
  `no_install_unknown_sources` set by a Device Owner policy (`dumpsys device_policy`), so
  `adb install` is refused until that restriction is lifted.

---

## Suggested order of work

1. Run the F1 probe — it decides whether the architecture is sound or needs flip gating back.
2. F3 and F2 (icon correctness): three lines each, and both are user-visible today.
3. F4, F6, F7 (robustness of the announce pipeline).
4. F5 if the app picker is meant to be usable.
5. Cleanup: F9, F10, then reconcile the docs (F11).

## Note on the live probe

The audit sent one `NOTIFY_LIST` to the device and restored the stock clock afterwards with
`CANCEL_ALL`; the logcat buffer was cleared before the probe. Nothing was installed or configured.

---

## Resolution log — 2026-08-24, `app-release.apk` 0.3 (versionCode 3)

Fixed, in the order they appear above:

| # | What changed |
|---|---|
| F3 | `syncIcon()` now returns early on `show=false` unless this process actually raised the icon (`iconShown != true`). The startup case — `iconShown` still `null`, wiping a genuine unread envelope — can no longer happen. |
| F4 | `onListenerConnected()` sends an unconditional `cancelPanel()` before `rebuild()`, so a panel stranded by a dead process is cleared on reconnect instead of waiting for a screen cycle. |
| F5 | `<queries>` block for the `smsto:`/`mmsto:` SENDTO intents added to the manifest; verified present in the packaged APK. The picker can now see installed SMS apps under targetSdk 30. |
| F6 | Dedup key is `postTime + "/" + sbn.key` (framework identity: user\|pkg\|id\|tag) instead of `postTime shl 4 or id`, which collided for ids above 15. Write-only field `announcedKey` deleted. |
| F7 | `FLAG_LOCAL_ONLY` removed from `skipFlags`; ongoing/summary/foreground-service filtering kept. |
| F8 | The parsed preview is rendered: new `panel_preview` TextView (2 lines, `11px`, hidden when the notification has no body, so the sender-only case stays pixel-identical to the native banner). |
| F9 | `flip/FlipMonitor.kt` moved to `docs/attic/` (unreferenced; restore it if F1 resolves against the current assumption). Empty `render/` and `res/xml/` directories removed. `CoverScreen.sendList()`/`keepLit()` intentionally kept as API surface. |
| F10 | Stray `app/AndroidManifest.xml` moved to `docs/attic/AndroidManifest.legacy.xml`. |
| F11 | `docs/EXTERNAL_SCREEN_API.md` corrected: §5.1 (panel hides the stock clock, white container background), §5.3 (single icon slot, last writer wins), §8.4 (PTT state is written but never read back — panels are lost, not resurrected), §8.5 (NOTIFY does **not** wake the screen), §9 (today's probe results), §11 (flip-open question added). `DESIGN.md` marked superseded with a design-vs-shipped delta table, and risks #2/#4/#5 closed from the decompile. |

Deliberately **not** changed, by decision: **F1** (no flip gating; the doc comments asserting the
receivers survive a flip cycle are untouched and still unverified) and **F2** (icon dedup across
Presentation recreation).

Nothing else in the pipeline was touched: the announce sequence, timings, watched-package defaults,
and the panel's native-banner look are as they were. Build is clean — the single lint error is the
intentional `targetSdk 30`, which `abortOnError = false` allows through.

### Post-install follow-up — 0.3, on device

Installed (`adb install -r` → `Success`; the device-policy restriction no longer
blocks it). Notification access was already granted from a previous install, so the listener bound
immediately.

The live log exposed a regression in the **F3** fix as first written: with `syncIcon()` refusing to
send `isShow=false`, `iconShown` stayed `null` forever, so the `iconShown != null` clause of the
dedup check in `rebuild()` never settled and every notification event re-ran the full path
(`state: unread=false` repeating in logcat). Fixed by recording the desired state even when the send
is declined — the guarantee "never hide an envelope we did not raise" is unchanged. Verified: zero
repeats after reinstall.

UI (`MainActivity.kt`) rewritten at the same time: dark themed cards (`values/colors.xml`,
`values/styles.xml`, `AppTheme` on `Theme.Material.NoActionBar`), a status card with a colour-coded
dot and a Start/Stop Service button, and an app list with real launcher icons and switches. The
cover-screen diagnostics card was dropped by request, and with it the preview path that briefly held
the `ptt-bright` wakelock.
