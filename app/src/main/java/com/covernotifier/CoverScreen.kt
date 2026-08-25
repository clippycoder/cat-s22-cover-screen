package com.covernotifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews

/**
 * Thin wrapper over SystemUI's cover-screen broadcast API.
 *
 * Receivers live in PresentationScreen (jadx: PresentationScreen.java:279-500) and, contrary to
 * earlier assumptions, are registered whenever the Presentation exists - which SURVIVES flip
 * open/close and cover-screen power cycles (verified against AOSP Presentation semantics +
 * decompiled lifecycle). Broadcasts sent while the flip is open simply set view state on the
 * sleeping cover display; wakeUp() is internally gated on the screen being off. So: no
 * flip-gating anywhere in this app.
 *
 * All sends must be package-targeted (-p com.android.systemui equivalent) or they are not
 * delivered on this build (EXTERNAL_SCREEN_API.md §9).
 */
object CoverScreen {

    const val SYSTEMUI = "com.android.systemui"

    /** Native relay: maps to the stock envelope icon + banner for the pkg key below. */
    const val ACTION_NOTIFICATION_CHANGE = "android.intent.action.notification.change"
    const val EXTRA_PKG = "pkg"
    const val EXTRA_IS_SHOW = "isShow"

    const val ACTION_NOTIFY = "ptt.sublcd.action.NOTIFY"
    const val ACTION_CANCEL = "ptt.sublcd.action.CANCEL"
    const val ACTION_NOTIFY_LIST = "ptt.sublcd.action.NOTIFY_LIST"
    const val ACTION_WAKEUP_SEC = "ptt.sublcd.action.WAKEUP_SEC"

    private const val EXTRA_ID = "_id"
    private const val EXTRA_NOTIFICATION = "_notification"
    private const val EXTRA_TITLE = "_title"
    private const val EXTRA_LIST = "_list"
    private const val EXTRA_IS_ON = "_isOn"

    /** Channel is only needed because Notification.Builder demands one; we never post it. */
    private const val CHANNEL_PAYLOAD = "cover_payload"

    private const val TAG = "CoverNotifier/API"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_PAYLOAD) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PAYLOAD, "Cover screen payload", NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
            )
        }
    }

    /**
     * Show/hide the native envelope icon. isShow=true also wakes the cover screen (8s) and
     * shows SystemUI's own banner from its cellular-SMS DB query (possibly stale for RCS -
     * which is why [sendPanel] exists).
     */
    fun sendNotificationChange(ctx: Context, pkg: String, show: Boolean) {
        send(
            ctx,
            Intent(ACTION_NOTIFICATION_CHANGE)
                .putExtra(EXTRA_PKG, pkg)
                .putExtra(EXTRA_IS_SHOW, show)
        )
    }

    /**
     * Render [rv] full-screen below the 16dp status strip (white background). Replaces the
     * clock/banner area entirely. Does NOT wake the screen - send [sendNotificationChange]
     * (true) first. State persists into the sublcd_present service, so always pair with
     * [cancelPanel].
     */
    fun sendPanel(ctx: Context, rv: RemoteViews, id: Int = 1) {
        val n = Notification.Builder(ctx, CHANNEL_PAYLOAD)
            .setSmallIcon(R.drawable.ic_stat_cover)
            .setCustomContentView(rv)
            .build()
        n.contentView = rv
        send(ctx, Intent(ACTION_NOTIFY).putExtra(EXTRA_ID, id).putExtra(EXTRA_NOTIFICATION, n))
    }

    fun cancelPanel(ctx: Context, id: Int = 1) =
        send(ctx, Intent(ACTION_CANCEL).putExtra(EXTRA_ID, id))

    /** Scrollable text list; external VOL+/- scroll it. Debug/testing helper. */
    fun sendList(ctx: Context, title: String, rows: List<String>) {
        send(
            ctx,
            Intent(ACTION_NOTIFY_LIST)
                .putExtra(EXTRA_ID, 1)
                .putExtra(EXTRA_TITLE, title)
                .putStringArrayListExtra(EXTRA_LIST, ArrayList(rows))
        )
    }

    /**
     * Holds/releases SystemUI's "ptt-bright" wakelock (acquiring wakes the screen). Never
     * leave this on: the cover LCD stays lit until released or SystemUI restarts.
     */
    fun keepLit(ctx: Context, on: Boolean) =
        send(ctx, Intent(ACTION_WAKEUP_SEC).putExtra(EXTRA_IS_ON, on))

    private fun send(ctx: Context, intent: Intent) {
        intent.setPackage(SYSTEMUI)
        try {
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w(TAG, "broadcast failed: " + intent.action, t)
        }
    }
}
