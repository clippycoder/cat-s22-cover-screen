# CAT S22 Flip — cover screen

Two things live here:

1. **`docs/EXTERNAL_SCREEN_API.md`** — a reverse-engineered reference for the CAT S22 Flip's
   external 128×128 cover display: how the framework drives it, and the undocumented broadcast API
   that any unprivileged app can use to draw on it. Probably the more useful half of this repo.
2. **Cover Notifier** — a small app built on that API. It puts the *correct* sender and message
   preview on the cover screen when a text arrives.

Device: CAT S22 FLIP, Android 11 (API 30), Qualcomm/Borqs/Mmax build.

---

## The short version of the API

The cover screen is a real, public secondary display (`displayId=1`, 128×128, 213 dpi,
`FLAG_PRESENTATION`, no `FLAG_PRIVATE`). Everything on it is drawn by SystemUI's
`PresentationScreen`, an `android.app.Presentation` that registers **unprotected, dynamically
registered broadcast receivers**. That makes them a de-facto public API — no permission, no root:

```bash
# Draw a scrollable list on the cover screen (external VOL+/- scroll it)
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.NOTIFY_LIST \
  --es _title "TITLE" --esa _list "one,two,three"

# Restore the stock clock
adb shell am broadcast -p com.android.systemui -a ptt.sublcd.action.CANCEL_ALL

# Screenshot the cover screen
adb exec-out screencap -d 1 -p > cover.png
```

`ptt.sublcd.action.NOTIFY` renders an arbitrary `RemoteViews` and needs a real app (parcelables
can't be attached from `am broadcast`). Full table of actions, extras, handler line references and
gotchas: **[docs/EXTERNAL_SCREEN_API.md](docs/EXTERNAL_SCREEN_API.md)**.

Things that cost time to discover, all documented there:

- Untargeted broadcasts are dropped — always `-p com.android.systemui`.
- `NOTIFY` does **not** wake the screen; only the SMS path and volume keys do.
- `NOTIFY_ICON` drives a *single* `ImageView`. Icons do not stack, last writer wins.
- A panel hides the stock clock, and its container has a **white** background.
- PTT state is written to the vendor service but never read back: panels are *lost* on
  Presentation recreation, not resurrected.

## The app

`com.covernotifier` is a `NotificationListenerService` and nothing else — no foreground service, no
alarms, no network, and only one declared manifest feature (`<queries>` for package visibility).
Per new message it runs:

1. `notification.change isShow=true` — wakes the cover screen and raises SystemUI's envelope icon.
2. +300 ms — `ptt.sublcd.action.NOTIFY` with a panel carrying the sender and preview parsed from the
   actual notification.
3. +8.3 s — `ptt.sublcd.action.CANCEL`, back to the clock.

Step 2 is the point of the app: SystemUI's own banner gets its sender from a
`Telephony.Sms` query, so for RCS and data messages — which never land in that database — it shows
the wrong name or nothing at all.

Settings: which messaging apps to watch, and whether to show message content or the sender alone.

Battery: nothing here is Doze-restricted. Verified under forced deep idle — the listener binding
survives and broadcasts still land.

## Build

Needs JDK 17+ (AGP 8.13). Android Studio supplies its own; for the command line export `JAVA_HOME`
or set `org.gradle.java.home` in `gradle.properties`.

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key — this is a sideload-only app. Installing needs
unknown-source installs permitted; a Device Owner policy with `no_install_unknown_sources` will
refuse it (`dumpsys device_policy` will say).

After installing, grant **notification access** (Settings → Apps → Special app access), which the
app's setup screen deep-links to.

## Repo layout

| Path | What |
|---|---|
| `app/` | The app. Four Kotlin files. |
| `docs/EXTERNAL_SCREEN_API.md` | The cover-screen API reference. |
| `DESIGN.md` | Original design doc. **Superseded** — kept for the record, with a delta table against what shipped. |
| `AUDIT.md` | Line-referenced code audit, its resolution log, and the one open architectural question. |
| `docs/attic/` | Removed-but-maybe-useful files. |

## Known limits

- **Flip-open behaviour is unverified.** The app assumes SystemUI's receivers outlive a flip cycle.
  Receivers are confirmed alive while the cover display is powered *off*, but `onStop()` unregisters
  all of them, so opening the flip may leave a window where announces are dropped. Test recipe in
  `AUDIT.md` (F1).
- After a cover-screen power cycle the status icon can go stale: SystemUI restores nothing, and the
  app dedups its icon sends (`AUDIT.md` F2).
- SMS/RCS apps only. Extending to WhatsApp/Signal/etc. means widening the picker and the filter.

## No vendor material here

No `SystemUI.apk`, no decompiler output, no cover-screen captures. The document cites class and line
references only; reproduce them from your own device with `jadx`/`apktool` (see §10 of the API doc).
Line numbers will drift across ROM builds and decompiler versions.

## License

MIT — see `LICENSE`.
