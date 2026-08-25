package com.covernotifier.msgs

import android.app.Notification
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
        watched = prefs.getStringSet(KEY_WATCHED, null) ?: WATCHED_DEFAULT
        showText = prefs.getBoolean(KEY_SHOW_TEXT, true)
        CoverScreen.ensureChannel(this)
    }

    override fun onListenerConnected() {
        instance = this
        Log.i(TAG, "listener connected")
        // PTT content persists until cancelled, so a panel drawn by a previous process instance
        // is still on screen while panelUp says false - clear() would skip it. Cancel blind.
        CoverScreen.cancelPanel(this)
        panelUp = false
        rebuild()
    }

    override fun onListenerDisconnected() {
        instance = null
        Log.w(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = rebuild()

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
        watched = if (pkgs.isEmpty()) WATCHED_DEFAULT else pkgs
        prefs.edit().putStringSet(KEY_WATCHED, watched).apply()
        rebuild()
    }

    fun setShowText(v: Boolean) {
        showText = v
        prefs.edit().putBoolean(KEY_SHOW_TEXT, v).apply()
        // Redraw immediately if a panel happens to be up, so the toggle is verifiable on the spot.
        if (panelUp) CoverScreen.sendPanel(this, buildPanel())
    }

    fun isEnabled() = enabled

    // ------------------------------------------------------------------ pipeline

    private fun rebuild() {
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
            unread && contentChanged -> announce()
            unread -> syncIcon(true)
            else -> clear()
        }
    }

    /** Full announce: icon + wake + our correct-sender panel for ~8s. */
    private fun announce() {
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

        // Google Messages posts transient bookkeeping notifications (bugle_broadcast_receiver_
        // channel) around every real event; without a content check each one re-triggers an
        // announce. Real message notifications always carry title/text.
        val parsed = parse(sbn)
        return !(TextUtils.isEmpty(parsed.first) && TextUtils.isEmpty(parsed.second))
    }

    /** Sender (or title) + single-line-ish preview, no MessagingStyle API (stub drift). */
    private fun parse(sbn: StatusBarNotification): Pair<String, String> {
        val extras = sbn.notification?.extras ?: return "" to ""
        val title = clean(extras.getCharSequence(Notification.EXTRA_TITLE))
        var body = clean(
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
        )
        // Picture/attachment messages often carry no text at all, or carry only the inline-image
        // placeholder that clean() strips. Without this they would be filtered out by accept() and
        // never announced.
        if (body.isEmpty() && hasAttachment(extras)) body = "Photo"
        if (body.length > MAX_PREVIEW) body = body.substring(0, MAX_PREVIEW - 1) + "…"
        return title to body
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

        val WATCHED_DEFAULT = setOf(
            "com.android.mms",
            "com.google.android.apps.messaging"
        )

        /** Two lines of ~11px text on a 128px screen; anything longer is ellipsised anyway. */
        private const val MAX_PREVIEW = 120

        private const val PANEL_DELAY_MS = 300L
        private const val PANEL_HOLD_MS = 8000L

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
                .getStringSet(KEY_WATCHED, null) ?: WATCHED_DEFAULT
    }
}
