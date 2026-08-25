# Cover Notifier — Design Document

**Status:** v1 scope locked after on-device reverse engineering (see §1a). Calendar support dropped.
**Goal:** Show the native **message icon on the external 128×128 cover screen while unread messages exist**, and remove it when there are none — replicating the native messaging app's behavior exactly.

## 1a. How the native messaging app ACTUALLY integrates (verified)

Reverse engineering `com.android.messaging` (`/product/app/messaging/messaging.apk`) found
**zero cover-screen code**: no `notification.change` sender, no `ptt.sublcd.*`, no hall/sublcd
references. A full system-image scan found the string `android.intent.action.notification.change`
in **no APK and no framework jar except SystemUI itself**.

The native pipeline is:

```
com.android.messaging posts/cancels a STANDARD Android notification
        │  (posts on new message: ReceiveSmsMessageAction → BugleNotifications.update;
        │   cancels on read: MarkAsReadAction → BugleNotifications.update → cancel)
        ▼
SystemUI's own NotificationListener (privileged, always-on)
        │  PresentationScreen.onNotificationPosted/Removed (:2084-2098)
        ▼
sendNotify(pkg, channelId, show) (:2102-2140) — package filter:
        com.android.messaging / com.android.mms / deskclock / dialer /
        server.telecom / org.codeaurora.dialer(channel-gated)
        ▼
self-relay broadcast android.intent.action.notification.change (pkg, isShow)
        ▼
PresentationScreen receiver → SMS icon + latest-sender banner
```

**Consequence:** the notification stream *is* the unread signal (posted = unread,
cancelled = read). Our app cannot inject notifications into that filter (package-name
gated), so we replicate the pipeline one step earlier: run our own
`NotificationListenerService`, watch the messaging app's notifications, and emit the
same `notification.change` broadcasts ourselves (the receiver is unprotected — §5.7
of EXTERNAL_SCREEN_API.md).

**Calendar: dropped.** No native calendar app exists on the device (only Google
Calendar, which contains no cover-screen integration — dex string scan: zero hits
for `notification.change`/`sublcd`/`HALL_COVER`).

---

**Original design notes (pre-RE, kept for context):**

**Status: SUPERSEDED (2026-08-24).** Kept as the record of the original plan. What actually
shipped (`app-release.apk` 0.3, sources in this directory) is a much smaller app; see `AUDIT.md`
for the review of it. The delta, so nobody reads this file as documentation of the code:

| This document specifies | What exists |
|---|---|
| Foreground service owning the pipeline (§3.5) | None — an OS-bound `NotificationListenerService` is the whole app |
| Calendar feed (§3.3) | Not implemented; no `READ_CALENDAR`, no poller |
| Persistent panel + per-app icon strip (§3.4) | One transient 8 s panel; icons are impossible as designed (see §6 #2 below) |
| Flip-state gating (§3.1, §5) | Deliberately absent — the code assumes the receivers outlive a flip cycle |
| All apps that post messages (§3.2) | SMS packages only, user-pickable |
**Goal:** An ordinary (non-privileged, sideloaded) Android app that surfaces **unread message notifications** and **upcoming calendar events** on the CAT S22 Flip's external 128×128 cover screen.
**Prerequisite reading:** `docs/EXTERNAL_SCREEN_API.md` — the reverse-engineered cover-screen API this design builds on. All `CoverScreen*` references below point at §5 of that doc.

---

## 1. Summary

A single foreground service app with two data feeds:

1. **Messages** — `NotificationListenerService` collects notifications from all installed messaging apps (WhatsApp, Signal, SMS, Telegram, Gmail, …).
2. **Calendar** — `CalendarContract` queries for upcoming events.

It renders both to the cover display through the **cooperative broadcast API** (`ptt.sublcd.action.*`), never fighting SystemUI for the display. Content is injected while the flip is closed and cleared on flip-open.

Non-goals for v1: custom full-screen `Presentation` takeover, responding to messages, cover-screen input handling beyond what SystemUI already does.

---

## 2. System context

```
┌─────────────────────────── phone (display 0, off when flipped) ───────────────────────────┐
│                                                                                           │
│  [Installed apps]──notifications──►[NotificationListenerService]──┐                       │
│  [CalendarProvider]──queries────────────────[CalendarPoller]──────┤                       │
│                                                                   ▼                       │
│                                          [CoverNotifier foreground service]               │
│                                           │  state machine, queue, renderer               │
└───────────────────────────────────────────┼───────────────────────────────────────────────┘
                                            │ unprotected broadcasts (no permissions)
                                            ▼
┌────────────────── SystemUI: PresentationScreen (display 1, 128×128) ──────────────────────┐
│   ptt.sublcd.action.NOTIFY_ICON  → status-strip icon (persistent)                         │
│   ptt.sublcd.action.NOTIFY       → arbitrary RemoteViews panel (persistent)               │
│   ptt.sublcd.action.CANCEL(_ALL) → restore clock                                          │
│   ptt.sublcd.action.WAKEUP_SEC   → keep screen lit                                        │
└───────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Components

### 3.1 `FlipMonitor`
Tracks whether the flip is closed. The cover-screen receivers only exist while the
PresentationScreen is alive (flip **closed**), so every render/clear action is gated on this.

- Register (dynamically, in the service) for sticky broadcast
  `android.os.action.HALL_COVER_CHANGED`, extra `cover: boolean`.
- On `cover=true`: run "on covered" routine (§5) — flush queued content, draw calendar.
- On `cover=false`: reset state; optionally `CANCEL_ALL` defensively (SystemUI tears
  the presentation down anyway, but PTT state persists in `sublcd_present`).
- Initial state: the broadcast is sticky-style — use
  `registerReceiver(null, filter)` to peek the last value at service start; if null,
  assume open and wait.
- Do **not** use `PowerManager.getIsHallCover()` (hidden API, blocklist risk).

### 3.2 `MessageCollector` (NotificationListenerService)
- Standard `NotificationListenerService`; user grants **Notification access** in
  Settings → Special app access. Show a first-run activity that deep-links there
  (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`).
- `onNotificationPosted`:
  - Filter: `isOngoing==false`, has non-empty `extras.getCharSequence(EXTRA_TEXT)` or
    `MessagingStyle`, not our own package, not music/system churn
    (`android.*, com.android.systemui` blocklist).
  - Extract: app label/icon (`getSmallIcon`, `loadDrawable`), sender
    (MessagingStyle `Person` name, else `EXTRA_TITLE`), preview text (truncate ~40 chars
    — fits 128 px width at the presentation font size).
  - Maintain an in-memory unread map: `pkg → {icon, latest sender, latest text, count}`.
- `onNotificationRemoved` / `onRankingUpdate`: decrement/clear that app's unread entry.
- On listener reconnect (`onListenerConnected`): seed with `activeNotifications` snapshot.
- Forward every change to the `Renderer` **if flip is closed**; else queue latest-per-app.

### 3.3 `CalendarPoller`
- Permission: `READ_CALENDAR` (runtime request in first-run activity).
- Query `CalendarContract.Instances` with the standard instances URI search range
  `now → now + 7 days`, sorted ascending; join title + begin time (+ location if set).
- Refresh triggers (no polling timer needed):
  - flip-close event,
  - `ACTION_TIME_TICK`-equivalent: `AlarmManager.setAndAllowWhileIdle` every 15 min
    while flip closed (doze-safe),
  - `ACTION_TIME_CHANGED`, `DATE_CHANGED`, `TIMEZONE_CHANGED` broadcasts.
- Produces: "next event" record `{title, startsIn}` for rendering.

### 3.4 `Renderer`
Converts state → cover-screen broadcasts. Two primitives:

**a) Unread icons (status strip):** one `NOTIFY_ICON` per app with an unread message.
- Build a `Notification` whose extras carry `icon_white`: a small (≈16–20 px) monochrome
  white bitmap of the app icon (alpha-threshold the loaded drawable; the handler shows
  white bitmaps untinted — see `docs/EXTERNAL_SCREEN_API.md` §5.3).
- Track sent icons; send `CANCEL_ICON` when an app's unread count returns to 0.

**b) Content panel:** `ptt.sublcd.action.NOTIFY` with a hand-built `RemoteViews`
(inflate from our own layout XML — RemoteViews accepts standard widgets only:
`FrameLayout/LinearLayout/RelativeLayout`, `TextView`, `ImageView`, `Chronometer`,
`ProgressBar`). Panel layout, 128×128 budget minus the ~18 px status strip SystemUI
keeps:

```
┌──────────────────────────────┐
│ ⚿ 3 unread          (icon row)│  ← per-app icons + counts
│──────────────────────────────│
│ Sarah: "call me when (1 line)│  ← latest message, sender bold
│──────────────────────────────│
│ ⏰ Dentist          (calendar)│
│    in 45 min                  │
└──────────────────────────────┘
```

- One RemoteViews, rebuilt and re-sent on every state change (cheap; last-write-wins).
- **Wake behavior:** `NOTIFY` triggers SystemUI's 8 s `wakeUp()`. That's desirable for
  new messages. For calendar refreshes, skip re-sending if content unchanged (hash the
  rendered state) to avoid pointless wakeups.
- **Keep-lit policy (v1):** do not hold `WAKEUP_SEC` by default; expose a setting
  "keep cover screen on while flipped" that acquires/releases it on flip transitions.
  Must release on flip-open and service stop (leak = permanently lit LCD).

### 3.5 `CoverNotifierService` (foreground)
- `foregroundServiceType="specialUse"` (or `connectedDevice`); persistent notification
  explaining what it does. Required so Doze doesn't kill the listener plumbing while
  the internal screen is off.
- Owns FlipMonitor, MessageCollector binding, CalendarPoller, Renderer.
- Restart: `START_STICKY`; re-seed state on `onListenerConnected`.

### 3.6 First-run `Activity`
- Request `READ_CALENDAR`, deep-link to notification-access settings, show current
  flip state (sanity check that FlipMonitor works), toggles:
  - per-app message forwarding (default: allowlist empty = all messaging apps),
  - calendar on/off,
  - keep-screen-on-while-flipped (default off).

---

## 4. Permissions inventory

| Permission | Type | Purpose |
|---|---|---|
| `READ_CALENDAR` | runtime | Calendar queries |
| Notification access | user grant (special) | Read notifications |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | normal | Keep service alive |
| `RECEIVE_BOOT_COMPLETED` | normal | Autostart after reboot |
| `POST_NOTIFICATIONS` (API 33+, future-proofing) | runtime | Our own status notification |

No `SEND_SMS`, no privileged APIs, no signature permissions. Everything is sideload-installable.

**Sending the `ptt.sublcd.*` / `notification.change` broadcasts requires no permission
at all** — the receivers are unprotected. On Android 11 there is no receiver-side
export check for dynamically registered receivers, which is exactly why the stock
PTT app works this way.

---

## 5. State machine

```
                 cover=true (HALL_COVER_CHANGED)
   OPEN ────────────────────────────────────────► COVERED
    ▲                                              │
    │ cover=false                                  │ normal operation:
    │  - cancel WAKEUP_SEC hold                    │  msg notification → icon + panel
    │  - clear unread map (stale by the time       │  calendar tick → panel refresh (deduped)
    │    the flip reopens)                         │
    └──────────────────────────────────────────────┘
```

Edge cases:
- **Broadcast sent while presentation dead** (race at flip transition): dropped
  silently by Android (no receiver). Mitigation: on `cover=true`, always re-render
  full state from the current map rather than relying on deltas.
- **Service killed while covered:** on restart, sticky broadcast restores flip state;
  listener reconnect reseeds unread map; re-render.
- **SystemUI reclaims panel** (its `refreshView()` on SCREEN_ON etc. — known from
  decompile, `PresentationScreen.java:2034-2061`): v1 accepts possible panel loss on
  screen-cycle; icons in the status strip are also re-evaluated by SystemUI there.
  Mitigation if it proves aggressive: re-send `NOTIFY` on
  `android.intent.action.SCREEN_ON` while covered (SystemUI's own receiver already
  refreshes at that moment; piggyback 250 ms later).

---

## 6. Known risks / open questions (test before building on top)

| # | Risk | Test |
|---|---|---|
| 1 | Does SystemUI's `refreshView()` wipe our `NOTIFY` panel on screen on/off cycles? | Send `NOTIFY`, press power button twice while covered, screencap `-d 1` each time |
| 2 | ~~How many `NOTIFY_ICON` icons fit in the status strip before overlap?~~ **Answered from the decompile: one.** `mPttIcon` is a single `ImageView` (`PresentationScreen.java:170`), so sends replace rather than stack. Per-app icons have to live inside the panel. | — |
| 3 | RemoteViews size limits at 128×128 (text truncation, font scaling) | Iterate layouts on device |
| 4 | ~~Does `sublcd_present` persist our `saveShowPtt(true)` across flip cycles and resurrect stale panels?~~ **Answered: no.** `mShow_Ptt` starts `false` in the constructor (`:258`) and is never read back, so a recreated Presentation shows the stock clock. The real failure mode is the opposite one — panels and icons are silently *lost*, and the app must re-send them. | — |
| 5 | ~~Doze: do `NOTIFY` broadcasts still wake the screen…~~ **Wrong premise: `NOTIFY` never wakes the screen** (`wakeUp()` has two callers, neither of them PTT). Waking is `notification.change`'s job. Doze behaviour of that path is still worth a soak. | Leave covered 1 h, send `notification.change`, observe |
| 6 | Notification-access apps on Android 11: any OEM battery killer (Qualcomm build) killing the listener? | 24 h soak |

All testable with the adb recipes in `docs/EXTERNAL_SCREEN_API.md` §7 — **do these before
writing the renderer**.

---

## 7. Project layout (when built)

```
cover-notifier/
  app/src/main/
    java/.../flip/       FlipMonitor.kt
    java/.../msgs/       MessageCollector.kt (NotificationListenerService)
    java/.../cal/        CalendarPoller.kt
    java/.../render/     Renderer.kt, CoverLayouts.kt (RemoteViews builders)
    java/.../            CoverNotifierService.kt, FirstRunActivity.kt, SettingsRepository.kt
    res/layout/          cover_panel.xml  (RemoteViews template, 128x128 budget)
    AndroidManifest.xml  (listener service + permission declarations)
  docs/                  this file
```

Min SDK 30 (device is API 30); target 30 to avoid post-11 broadcast/parcelable
restrictions (`Notification` as intent extra is fine on 30; targeting 31+ flags
`FLAG_IMMUTABLE` requirements on any PendingIntents used).

## 8. Milestones

1. **M0 — probe (no app):** risks #1/#2/#4 via adb. Decision gate: if SystemUI wipes
   panels aggressively, pivot v1 to icon-only + banner via `notification.change`.
2. **M1 — skeleton:** service + FlipMonitor + flip-state debug overlay on cover screen
   (renders "COVERED/OPEN" via NOTIFY). Validates end-to-end plumbing.
3. **M2 — messages:** listener + icons + latest-message panel.
4. **M3 — calendar:** poller + next-event row.
5. **M4 — polish:** per-app settings, keep-lit toggle, 24 h soak.
