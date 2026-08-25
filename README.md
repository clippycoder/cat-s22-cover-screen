# Cover Notifier

Puts the right sender and message preview on the **CAT S22 Flip**'s external 128×128 cover screen.

The cover screen only answers to a short, hardcoded list of the phone's built-in apps, and your
actual messaging app almost certainly isn't on it — Google Messages, the default here, has no idea
the external display exists. So a text arrives, the cover screen keeps showing the clock, and you
open the phone to find out who wrote to you.

Cover Notifier bridges that gap. It watches notifications from the messaging apps you pick and draws
its own panel on the cover screen with the sender and, if you want it, the message text. It appears
for about eight seconds and then the clock comes back.

It is a notification listener and nothing else — no foreground service, no network access, no
analytics. It never dismisses, replies to, or alters your notifications, and it costs no measurable
battery: it does no polling and only reacts to notifications the system was already delivering.

Requires Android 11 on the CAT S22 FLIP. The cover-screen mechanism is specific to this device.

---

## Installing

1. Download `cover-notifier.apk` from [Releases](../../releases) and open it on the phone, or
   sideload it:

   ```bash
   adb install -r cover-notifier.apk
   ```

2. Open **Cover Notifier** and grant **notification access** — the setup screen has a button that
   deep-links straight to the right settings page (Settings → Apps → Special app access →
   Notification access). Without it the app can't see messages and does nothing.

3. The status card turns green and reads **Running** once access is granted and the system has bound
   the listener.

### Settings

| Setting | What it does |
|---|---|
| **Start / Stop Service** | Turns the relay on and off without revoking notification access. |
| **Show message content on the display** | Off shows the sender only — useful if you don't want message text readable on a closed phone. |
| **Notify for silent messages** | Off by default. A message from a chat or app you have silenced stays off the cover screen, with no icon either. |
| **Announce messages from** | Which apps to watch. Google Messages and your default texting app are on out of the box. |

Toggling the message-content setting while the cover screen is showing a panel redraws it
immediately, so you can see the difference straight away.

Only message notifications are relayed, so an app's own alerts — "message not sent" and the like —
stay off the cover screen. An app that notifies you some other way can sit switched on and stay
silent; the list marks those as *no messages seen yet*.

Google Messages is the app this is built for. Everything else is **experimental**.

## Releases and building

Prebuilt APKs are on the [Releases](../../releases) page. They are signed with a debug key — this is
a sideload-only app, so if you later install a build signed with a different key you'll have to
uninstall first.

To build it yourself you need JDK 17+ (Android Gradle Plugin 8.13). Android Studio ships its own; for
the command line, export `JAVA_HOME` or set `org.gradle.java.home` in `gradle.properties`.

```bash
git clone https://github.com/clippycoder/cat-s22-cover-screen
cd cat-s22-cover-screen
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Minimum and target SDK are both 30, deliberately: the app talks to SystemUI through undocumented
broadcasts, and targeting 31+ would pull in restrictions this device's ROM was never built for.

## License

GPLv3 — see [LICENSE](LICENSE).
