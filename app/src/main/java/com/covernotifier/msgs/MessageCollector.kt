package com.covernotifier.msgs

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.util.Log
import com.covernotifier.CoverScreen

/**
 * The whole app (v3): an OS-managed NotificationListenerService that watches messaging
 * notifications and drives the cover screen. No foreground service - the system keeps this
 * listener bound while notification access is granted, and rebinds + reconnects after process
 * death (onListenerConnected re-syncs), so it is self-healing under Doze/memory pressure.
 *
 * Announce pipeline per new message (hybrid, fixes the wrong-sender-name bug):
 *   1. notification.change isShow=true  -> wakeUp + persistent envelope icon + SystemUI's
 *      banner (whose text comes from its own cellular-SMS DB query - WRONG for RCS/data
 *      messages, which never land in Telephony.Sms)
 *   2. +300ms: ptt.sublcd.action.NOTIFY panel carrying the CORRECT sender/preview parsed from
 *      the actual notification - fully replaces the clock/banner area (status strip stays)
 *   3. +8.3s: ptt.sublcd.action.CANCEL -> back to clock + icon (SystemUI's pending 8s
 *      banner-hide handler normalizes any inner view state; icon untouched)
 *
 * Native parity notes (from decompiled SystemUI): the Presentation instance survives flip
 * open/close and screen power cycles, and its receivers are always registered - so no
 * flip-gating anywhere. Announces made while the flip is open simply set view state on the
 * sleeping cover display and are already correct at the next flip close.
 */
class MessageCollector : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences

    private var enabled = true
    private var watched: Set<String> = WATCHED_DEFAULT

    /** When false the panel shows the sender only - the preview line is never drawn. */
    private var showText = true

    /** When false a message you have silenced - muted chat, silenced app - is not announced. */
    private var notifySilent = false


    /** Current derived state. */
    private var unread = false
    private var newestKey = ""
    private var newestSender = ""
    private var newestText = ""

    /** Last state pushed to SystemUI (dedup). Null = nothing pushed yet. */
    private var iconShown: Boolean? = null
    private var panelUp = false

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        watched = prefs.getStringSet(KEY_WATCHED, null) ?: defaultWatched(this)
        showText = prefs.getBoolean(KEY_SHOW_TEXT, true)
        notifySilent = prefs.getBoolean(KEY_NOTIFY_SILENT, false)
        CoverScreen.ensureChannel(this)
    }

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "listener connected")
        // PTT content persists until canceled, so a panel drawn by a previous process instance
        // is still on screen while panelUp says false - clear() would skip it. Cancel blind.
        CoverScreen.cancelPanel(this)
        panelUp = false
        // Clear the envelope unconditionally on connect. Normally we never hide an icon this
        // process did not raise, but a previous process can die holding one lit with nobody left
        // to clear it - and on this ROM nothing else drives that indicator anyway. The seed below
        // puts it straight back if messages are actually waiting.
        CoverScreen.sendNotificationChange(this, RELAY_PKG, false)
        iconShown = false
        // Seed, do not announce: the listener reconnects on every process start, and whatever is
        // waiting at that moment is old news. Re-announcing wakes the cover screen for messages
        // already read.
        rebuild(seed = true)
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.w(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            noteMessagingPackage(it)
            logDecision(it)
        }
        rebuild()
    }

    /**
     * Remembers every package seen posting a conversation - watched or not. The picker uses this to
     * float apps that genuinely message you above the rest of the installed list, which beats
     * guessing from intent filters.
     */
    private fun noteMessagingPackage(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || HIDDEN_PACKAGES.contains(pkg)) return
        val n = sbn.notification ?: return
        if (!isConversation(n)) return
        val seen = prefs.getStringSet(KEY_SEEN, null).orEmpty()
        if (seen.contains(pkg) || seen.size >= MAX_SEEN) return
        Log.i(TAG, "first conversation notification from " + pkg)
        prefs.edit().putStringSet(KEY_SEEN, seen + pkg).apply()
    }

    /**
     * One line per notification from a watched app, recording what the filter decided and why.
     * The filter is written against documented notification shapes; this is how a real message
     * confirms them, and how a false rejection gets explained later.
     */
    private fun logDecision(sbn: StatusBarNotification) {
        if (!watched.contains(sbn.packageName)) return
        val n = sbn.notification ?: return
        Log.i(
            TAG,
            "seen " + sbn.packageName +
                " channel=" + n.channelId +
                " category=" + n.category +
                " template=" + n.extras?.getString(Notification.EXTRA_TEMPLATE) +
                " flags=0x" + Integer.toHexString(n.flags) +
                " silenced=" + isSilenced(sbn) +
                " dnd=" + isDndSuppressed(sbn) +
                " accepted=" + accept(sbn)
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rebuild()

    fun setEnabled(v: Boolean) {
        enabled = v
        prefs.edit().putBoolean(KEY_ENABLED, v).apply()
        if (v) {
            rebuild()
        } else {
            clear()
        }
    }

    fun setWatched(pkgs: Set<String>) {
        // An empty set is a legitimate choice now that the picker lists every app - it means
        // "announce nothing", not "fall back to the defaults".
        watched = pkgs
        prefs.edit().putStringSet(KEY_WATCHED, watched).apply()
        rebuild()
    }

    fun setNotifySilent(v: Boolean) {
        notifySilent = v
        prefs.edit().putBoolean(KEY_NOTIFY_SILENT, v).apply()
        rebuild()
    }

    fun setShowText(v: Boolean) {
        showText = v
        prefs.edit().putBoolean(KEY_SHOW_TEXT, v).apply()
        // Redraw immediately if a panel happens to be up, so the toggle is verifiable on the spot.
        if (panelUp) CoverScreen.sendPanel(this, buildPanel())
    }

    fun isEnabled() = enabled

    /**
     * Whether an app's notification channels look like a messenger's, without waiting for it to
     * post anything. Only a bound listener may read another package's channels, which is why this
     * lives on the service rather than in the UI.
     *
     * Two tells, both set by the app itself:
     *   - a channel carrying a conversation id, which Android only creates for apps using
     *     conversation shortcuts (Google Messages and WhatsApp do);
     *   - AudioAttributes usage USAGE_NOTIFICATION_COMMUNICATION_INSTANT, which is literally the
     *     app declaring "this channel is instant messaging".
     *
     * Channels exist only after the app has run once and registered them, so a freshly installed
     * app can read as unknown until first launch.
     */
    fun channelsSuggestMessaging(pkg: String): Boolean = try {
        getNotificationChannels(pkg, android.os.Process.myUserHandle()).any { channel ->
            channel.conversationId != null ||
                channel.audioAttributes?.usage ==
                android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT
        }
    } catch (t: Throwable) {
        // Not connected yet, or the platform refused the lookup.
        false
    }

    // ------------------------------------------------------------------ pipeline

    private fun rebuild(seed: Boolean = false) {
        val active = try {
            activeNotifications
        } catch (t: Throwable) {
            Log.w(TAG, "activeNotifications unavailable", t)
            return
        } ?: return

        var anyUnread = false
        var newest: StatusBarNotification? = null
        for (sbn in active) {
            if (!accept(sbn)) continue
            anyUnread = true
            if (newest == null || sbn.postTime > newest.postTime) newest = sbn
        }

        // sbn.key is the framework's own identity for a notification (user|pkg|id|tag);
        // pairing it with postTime distinguishes an edit-in-place from a genuinely new message.
        val newKey = newest?.let { it.postTime.toString() + "/" + it.key } ?: ""
        val contentChanged = newest != null && (newKey != newestKey || !unread)
        if (anyUnread == unread && newKey == newestKey && iconShown != null) return

        unread = anyUnread
        if (newest != null) {
            newestKey = newKey
            val parsed = parse(newest)
            newestSender = parsed.first
            newestText = parsed.second
        }

        Log.i(TAG, "state: unread=" + unread + " key=" + newestKey +
                " sender=" + newestSender)
        when {
            !enabled -> clear()
            unread && contentChanged && !seed -> announce()
            unread -> syncIcon(true)
            else -> clear()
        }
    }

    /** Full announce: icon + wake + our correct-sender panel for ~8s. */
    private fun announce() {
        Log.i(TAG, "announce: " + newestSender)
        CoverScreen.sendNotificationChange(this, RELAY_PKG, true)
        iconShown = true
        handler.removeCallbacks(showPanel)
        handler.removeCallbacks(hidePanel)
        handler.postDelayed(showPanel, PANEL_DELAY_MS)
    }

    private val showPanel = Runnable {
        if (!unread) return@Runnable
        CoverScreen.sendPanel(this, buildPanel())
        panelUp = true
        handler.postDelayed(hidePanel, PANEL_HOLD_MS)
    }

    private val hidePanel = Runnable {
        if (!panelUp) return@Runnable
        CoverScreen.cancelPanel(this)
        panelUp = false
    }

    /** Icon-state-only sync (no banner replay). */
    private fun syncIcon(show: Boolean) {
        if (iconShown == show) return
        // This is SystemUI's own unread-SMS envelope, not a private slot: hiding it when we never
        // raised it wipes a genuine indicator. Happens on every fresh connect otherwise, where
        // iconShown is still null.
        // Remember the desired state even when we decline to send it, or the dedup check in
        // rebuild() (iconShown != null) never settles and every notification event re-runs.
        if (!show && iconShown != true) {
            iconShown = false
            return
        }
        CoverScreen.sendNotificationChange(this, RELAY_PKG, show)
        iconShown = show
    }

    private fun clear() {
        handler.removeCallbacks(showPanel)
        handler.removeCallbacks(hidePanel)
        if (panelUp) {
            CoverScreen.cancelPanel(this)
            panelUp = false
        }
        syncIcon(false)
    }

    /**
     * Pixel replica of the native banner. Native (PresentationScreen.setWallPaper, :1330-1343):
     * background = WallpaperManager.getExternalDrawable() (the EXTERNAL display's own wallpaper)
     * scaled 128x128, fallback solid black. There is no external wallpaper set on this device,
     * so this normally resolves to black - exactly like native. Deliberately NO fallback to the
     * internal phone wallpaper (that's what made the panel flash white).
     */
    private fun buildPanel(): android.widget.RemoteViews {
        val rv = android.widget.RemoteViews(packageName, com.covernotifier.R.layout.cover_panel)
        rv.setTextViewText(com.covernotifier.R.id.panel_text, newestSender.ifEmpty { "New message" })
        val preview = if (showText) newestText else ""
        if (preview.isEmpty()) {
            rv.setViewVisibility(com.covernotifier.R.id.panel_preview, android.view.View.GONE)
        } else {
            rv.setViewVisibility(com.covernotifier.R.id.panel_preview, android.view.View.VISIBLE)
            rv.setTextViewText(com.covernotifier.R.id.panel_preview, preview)
        }
        val wall = externalWallpaper()
        if (wall != null) {
            Log.i(TAG, "panel bg: external wallpaper")
            rv.setImageViewBitmap(com.covernotifier.R.id.panel_bg,
                android.graphics.Bitmap.createScaledBitmap(wall, 128, 128, true))
        } else {
            Log.i(TAG, "panel bg: black fallback (no external wallpaper)")
            rv.setInt(com.covernotifier.R.id.panel_bg, "setBackgroundColor",
                -16777216) // black - same fallback PresentationScreen uses
        }
        return rv
    }

    /** Vendor hidden API: WallpaperManager.getExternalDrawable() (external display wallpaper). */
    private fun externalWallpaper(): android.graphics.Bitmap? = try {
        val wm = android.app.WallpaperManager.getInstance(this)
        val m = android.app.WallpaperManager::class.java.getMethod("getExternalDrawable")
        val d = m.invoke(wm) as? android.graphics.drawable.Drawable ?: return null
        drawableToBitmap(d)
    } catch (t: Throwable) {
        Log.w(TAG, "getExternalDrawable failed: " + t.message)
        null
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable): android.graphics.Bitmap? {
        if (d is android.graphics.drawable.BitmapDrawable) return d.bitmap
        val w = d.intrinsicWidth.coerceAtLeast(1)
        val h = d.intrinsicHeight.coerceAtLeast(1)
        return android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            .also { d.setBounds(0, 0, w, h); d.draw(android.graphics.Canvas(it)) }
    }

    // ------------------------------------------------------------------ filtering

    private fun accept(sbn: StatusBarNotification): Boolean {
        if (HIDDEN_PACKAGES.contains(sbn.packageName)) return false
        if (!watched.contains(sbn.packageName)) return false
        val n = sbn.notification ?: return false
        // Deliberately no FLAG_LOCAL_ONLY here: it means "do not bridge this to other devices",
        // which several messaging apps set on perfectly real messages.
        val skipFlags = Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_GROUP_SUMMARY or
            Notification.FLAG_FOREGROUND_SERVICE
        if (n.flags and skipFlags != 0) return false

        if (!isConversation(n)) return false
        if (!notifySilent && isSilenced(sbn)) return false

        // Even a conversation notification is useless with nothing displayable in it.
        val parsed = parse(sbn)
        return !(TextUtils.isEmpty(parsed.first) && TextUtils.isEmpty(parsed.second))
    }

    /**
     * Silenced by you: a muted conversation, or an app whose channel you set below Default.
     *
     * Ranking importance folds in per-channel and per-conversation overrides, so this follows what
     * you already set on the phone. Lighting the cover screen for a chat you deliberately muted
     * defeats the point of having muted it.
     *
     * Deliberately separate from [isDndSuppressed]: this is a standing choice about one chat, that
     * one is a temporary mode covering everything.
     */
    private fun isSilenced(sbn: StatusBarNotification): Boolean {
        val ranking = rankingOf(sbn) ?: return false
        return ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
    }

    /**
     * Held back by Do Not Disturb right now. Diagnostic only - deliberately NOT a gate: a lit
     * 128x128 screen makes no sound, so Do Not Disturb has no reason to blank the cover screen.
     * Kept because it explains, in the log, why a message did or did not alert on the phone itself.
     */
    private fun isDndSuppressed(sbn: StatusBarNotification): Boolean {
        val ranking = rankingOf(sbn) ?: return false
        return !ranking.matchesInterruptionFilter()
    }

    private fun rankingOf(sbn: StatusBarNotification): Ranking? {
        val map = currentRanking ?: return null
        val ranking = Ranking()
        return if (map.getRanking(sbn.key, ranking)) ranking else null
    }

    /**
     * A real conversation, as opposed to the app talking about itself.
     *
     * Google Messages posts on a dozen channels - bugle_rcs_not_delivered_channel for send
     * failures, bugle_connected_to_web_channel_v, bugle_reminder_channel, bugle_auto_moved_spam_
     * channel and so on - and every one of them used to reach the cover screen looking like a
     * message. Two signals separate a conversation from all of that, either sufficient:
     *
     *   - MessagingStyle, which every modern messaging app uses for an actual exchange (Google
     *     Messages, WhatsApp, Signal, Discord DMs) and none of the prompt/error notifications do;
     *   - CATEGORY_MESSAGE, for simpler apps that never adopted MessagingStyle.
     *
     * Deliberately an allowlist with no escape hatch: a blocklist of categories or bugle_* channel
     * names loses to the next channel rename, and an "announce anything from this app" override
     * would put the noise straight back.
     */
    private fun isConversation(n: Notification): Boolean {
        val template = n.extras?.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        return template.endsWith("MessagingStyle") || n.category == Notification.CATEGORY_MESSAGE
    }

    /** What the panel shows: a title line and a preview line. */
    private fun parse(sbn: StatusBarNotification): Pair<String, String> {
        val extras = sbn.notification?.extras ?: return "" to ""
        val (title, body) = fromMessagingStyle(extras) ?: fromLegacyExtras(extras)
        return title to (if (body.length > MAX_PREVIEW) body.substring(0, MAX_PREVIEW - 1) + "…" else body)
    }

    /**
     * Reads the actual message out of MessagingStyle's own history.
     *
     * The extras hold a Parcelable[] of Bundles, one per message. Reading those keys directly
     * rather than going through Notification.MessagingStyle.extractMessagingStyleFromNotification()
     * is deliberate: the API route already failed here once (see git history), and this layout has
     * been stable since API 24.
     *
     * Group chats are titled with the conversation, not the speaker - on a 128px line the useful
     * thing is "which chat", and the speaker still rides along in the preview.
     */
    private fun fromMessagingStyle(extras: android.os.Bundle): Pair<String, String>? {
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null

        var newest: android.os.Bundle? = null
        var newestTime = Long.MIN_VALUE
        for (item in raw) {
            val msg = item as? android.os.Bundle ?: continue
            // A null sender means the message is from you. With RCS multi-device your own replies
            // sync back into the notification, and announcing those would be nonsense.
            if (senderOf(msg).isEmpty()) continue
            val time = msg.getLong(KEY_TIME, 0L)
            if (newest == null || time >= newestTime) {
                newest = msg
                newestTime = time
            }
        }
        val msg = newest ?: return null

        val speaker = senderOf(msg)
        val text = clean(msg.getCharSequence(KEY_TEXT)).ifEmpty { attachmentLabel(msg) }
        val conversation = clean(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
        val isGroup = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        return if (isGroup || conversation.isNotEmpty()) {
            val title = conversation.ifEmpty { clean(extras.getCharSequence(Notification.EXTRA_TITLE)) }
            title to if (text.isEmpty()) "" else speaker + ": " + text
        } else {
            speaker.ifEmpty { clean(extras.getCharSequence(Notification.EXTRA_TITLE)) } to text
        }
    }

    /** Pre-MessagingStyle shape, and the fallback whenever the message history is unusable. */
    private fun fromLegacyExtras(extras: android.os.Bundle): Pair<String, String> {
        val title = clean(extras.getCharSequence(Notification.EXTRA_TITLE))
        var body = clean(
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
        )
        // Picture messages often carry no text at all, or only the inline-image placeholder that
        // clean() strips. Without this they would fail accept()'s content check and never announce.
        if (body.isEmpty() && hasAttachment(extras)) body = "Photo"
        return title to body
    }

    /** Person name, legacy sender string, or empty - empty also meaning "this one is from you". */
    private fun senderOf(msg: android.os.Bundle): String {
        val person = msg.getParcelable<android.app.Person>(KEY_SENDER_PERSON)
        val name = clean(person?.name)
        if (name.isNotEmpty()) return name
        return clean(msg.getCharSequence(KEY_SENDER))
    }

    /** A message whose payload is a file rather than words. */
    private fun attachmentLabel(msg: android.os.Bundle): String {
        val mime = msg.getString(KEY_DATA_MIME_TYPE) ?: return ""
        return if (mime.startsWith("image/")) "Photo" else "Attachment"
    }

    /**
     * Notification text can contain inline-image spans, which flatten to U+FFFC (and occasionally
     * other control characters) - on the cover screen those render as tofu boxes.
     */
    private fun clean(cs: CharSequence?): String = cs?.toString()
        ?.replace('\uFFFC', ' ')
        ?.replace(Regex("\\p{Cntrl}"), " ")
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()

    /** BigPictureStyle / MMS attachment, without ever touching the bitmap itself. */
    private fun hasAttachment(extras: android.os.Bundle): Boolean =
        extras.containsKey(Notification.EXTRA_PICTURE) ||
            extras.getString(Notification.EXTRA_TEMPLATE)?.contains("BigPictureStyle") == true

    companion object {
        private const val TAG = "CoverNotifier/Msgs"
        private const val PREFS_FILE = "cover_notifier"
        const val KEY_ENABLED = "enabled"
        const val KEY_WATCHED = "watched"
        const val KEY_SHOW_TEXT = "show_text"
        const val KEY_SEEN = "seen_messaging"
        const val KEY_NOTIFY_SILENT = "notify_silent"

        /** MessagingStyle.Message bundle keys - stable since API 24, not public constants. */
        private const val KEY_TEXT = "text"
        private const val KEY_TIME = "time"
        private const val KEY_SENDER = "sender"
        private const val KEY_SENDER_PERSON = "sender_person"
        private const val KEY_DATA_MIME_TYPE = "type"

        private const val MAX_SEEN = 50

        /**
         * The relay broadcast always uses this package string - it is just the key
         * PresentationScreen checks to route to its SMS icon path.
         */
        const val RELAY_PKG = "com.android.messaging"

        /**
         * Never watched, never listed. The AOSP Messaging preload at /product/app/messaging is on
         * this ROM but is not the SMS role holder (Google Messages is), so it posts nothing - it
         * only clutters the picker. Excluded here as well as in the UI so a package left over in
         * saved prefs stays inert.
         *
         * Unrelated to RELAY_PKG above, which is a routing key for SystemUI, not a watched app.
         */
        val HIDDEN_PACKAGES = setOf("com.android.messaging")

        /** Hardcoded: Google Messages is the point of the app. */
        val WATCHED_DEFAULT = setOf("com.google.android.apps.messaging")

        /**
         * Known messengers, listed by name because Android offers no way to recognise them.
         *
         * Checked against the real manifests: Signal declares no CATEGORY_APP_MESSAGING and dropped
         * its SMS handlers entirely; Telegram declares neither and randomises its channel ids
         * without ever setting a conversation id; WhatsApp and Discord use fixed channel sets with
         * no conversation ids either. So every programmatic signal this app has misses all four,
         * and they would stay invisible until their first message arrived.
         *
         * Curated lists are also what shipping products do here - CoverScreenOS drives its caller
         * ID the same way, and the watch companions all fall back to per-app toggles over the
         * installed list.
         *
         * Being listed only offers an app in the picker. Nothing is announced until the user turns
         * it on AND the app posts a message-style notification.
         */
        val KNOWN_MESSENGERS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.thoughtcrime.securesms",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.discord",
            "com.facebook.orca",
            "com.facebook.mlite",
            "com.viber.voip",
            "jp.naver.line.android",
            "com.kakao.talk",
            "com.tencent.mm",
            "com.skype.raider",
            "com.microsoft.teams",
            "com.Slack",
            "com.google.android.apps.dynamite",
            "com.snapchat.android",
            "com.groupme.android",
            "ch.threema.app",
            "com.wire",
            "im.vector.app",
            "network.loki.messenger",
            "com.imo.android.imoim"
        )

        /**
         * Defaults plus whatever actually holds the SMS role, so someone whose texting app is not
         * Messages is not left with a list that ignores their texts.
         */
        fun defaultWatched(ctx: Context): Set<String> {
            val role = try {
                android.provider.Telephony.Sms.getDefaultSmsPackage(ctx)
            } catch (_: Throwable) {
                null
            }
            return if (role == null || HIDDEN_PACKAGES.contains(role)) {
                WATCHED_DEFAULT
            } else {
                WATCHED_DEFAULT + role
            }
        }

        /** Two lines of ~11px text on a 128px screen; anything longer is truncated anyway. */
        private const val MAX_PREVIEW = 120

        /**
         * Zero on purpose. The wake broadcast makes SystemUI paint its own banner, whose sender
         * comes from the newest row in the telephony database - stale or plain wrong for RCS, which
         * is the bug this app exists to fix. Any delay here lets that wrong name show before our
         * panel covers it. Both broadcasts reach the same receiver on SystemUI's main thread in
         * order, so posting immediately means the banner is overwritten before a frame renders.
         */
        private const val PANEL_DELAY_MS = 0L
        /**
         * Must OUTLAST SystemUI's own banner timeout. The wake broadcast schedules
         * refreshViewDelayed(8000), whose handler (PresentationScreen.java:687-689) hides
         * notifyInfo - the stale-sender banner nested inside normal_info_screen. Cancelling our
         * panel before that fires would uncover the wrong name for the remainder of the timeout.
         */
        private const val PANEL_HOLD_MS = 8300L

        @Volatile
        var instance: MessageCollector? = null
            private set

        /** Notification access is a special-access grant; this is the cheap way to check it. */
        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            return flat.split(':').any { it.startsWith(ctx.packageName + "/") }
        }

        fun loadShowText(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHOW_TEXT, true)

        fun loadWatched(ctx: Context): Set<String> =
            ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getStringSet(KEY_WATCHED, null) ?: defaultWatched(ctx)

        fun loadNotifySilent(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getBoolean(KEY_NOTIFY_SILENT, false)

        /** Packages observed posting a conversation notification, watched or not. */
        fun loadSeenMessaging(ctx: Context): Set<String> =
            ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getStringSet(KEY_SEEN, null).orEmpty()
    }
}
