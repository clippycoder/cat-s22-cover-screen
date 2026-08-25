package com.covernotifier

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.covernotifier.msgs.MessageCollector

/**
 * Setup and status. Two cards: is it running, and which apps it watches.
 *
 * Built in code rather than XML - the phone panel is 480x640 (320dp wide), so this is one narrow
 * column, and every row comes from runtime state anyway.
 */
class MainActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var statusDetail: TextView
    private lateinit var permDot: View
    private lateinit var permText: TextView
    private lateinit var primary: TextView
    private lateinit var accessButton: View
    private lateinit var appList: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cover_notifier", Context.MODE_PRIVATE)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(20))
        }
        column.addView(title())
        column.addView(statusCard(), cardParams())
        column.addView(optionsCard(), cardParams())
        column.addView(appsCard(), cardParams())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(color(R.color.bg))
            isFillViewport = true
            addView(
                column,
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---------------------------------------------------------------- views

    private fun title() = TextView(this).apply {
        text = getString(R.string.app_name)
        setTextColor(color(R.color.text_primary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), 0, dp(2), dp(14))
    }

    private fun statusCard(): View {
        val card = card()

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
                .apply { rightMargin = dp(9) }
        }
        row.addView(statusDot)
        statusText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
        }
        row.addView(statusText)
        card.addView(row)

        statusDetail = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(R.color.text_dim))
            setPadding(0, dp(5), 0, 0)
        }
        card.addView(statusDetail)

        val permRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }
        permDot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                .apply { rightMargin = dp(8) }
        }
        permRow.addView(permDot)
        permText = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(R.color.text_dim))
        }
        permRow.addView(permText)
        card.addView(permRow)

        primary = filledButton { toggleRelay() }
        card.addView(primary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(14) })

        accessButton = outlineButton("Grant notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        card.addView(accessButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ).apply { topMargin = dp(8) })

        return card
    }

    private fun optionsCard(): View {
        val card = card()
        card.addView(
            settingRow(
                "Show message content:",
                MessageCollector.loadShowText(this)
            ) { setShowText(it) }
        )
        card.addView(
            settingRow(
                "Notify for silent messages:",
                MessageCollector.loadNotifySilent(this)
            ) { setNotifySilent(it) }
        )
        return card
    }

    private fun settingRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = checked
            tintSwitch(this)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        })
        return row
    }

    private fun appsCard(): View {
        val card = card()
        card.addView(TextView(this).apply {
            text = "Announce messages from:"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
            setPadding(0, 0, 0, dp(6))
        })
        card.addView(TextView(this).apply {
            // Kept to one line: this sits above a list on a 320dp-wide screen.
            text = "Experimental; only message notifications work"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(color(R.color.text_dim))
            setPadding(0, dp(2), 0, dp(4))
        })
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(appList)
        return card
    }

    private fun appRow(pkg: String, checked: Boolean, seen: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(ImageView(this).apply {
            iconFor(pkg)?.let { setImageDrawable(it) }
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
                .apply { rightMargin = dp(10) }
        })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labels.addView(TextView(this).apply {
            text = labelFor(pkg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(R.color.text_primary))
            maxLines = 1
        })
        // Only standard message notifications are announced, so an app can be switched on and stay
        // silent. Saying so beats leaving the user to wonder which of the two it is.
        if (checked && !seen) {
            labels.addView(TextView(this).apply {
                text = "no messages seen yet"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(color(R.color.text_dim))
                maxLines = 1
            })
        }
        row.addView(labels)
        row.addView(Switch(this).apply {
            isChecked = checked
            tintSwitch(this)
            setOnCheckedChangeListener { _, on -> setWatched(pkg, on) }
        })
        return row
    }

    // ---------------------------------------------------------------- state

    private fun refresh() {
        val access = MessageCollector.hasAccess(this)
        val connected = MessageCollector.instance != null
        val relay = relayEnabled()

        val (dot, headline, detail) = when {
            access && relay && connected ->
                Triple(R.color.ok, "Running", "New messages show on the cover screen.")
            !access ->
                Triple(R.color.bad, "Setup needed", "Notification access is not granted yet.")
            !relay ->
                Triple(R.color.warn, "Stopped", "The relay is switched off.")
            else ->
                Triple(R.color.warn, "Waiting", "Access granted, listener not bound yet.")
        }
        statusDot.background = dot(color(dot))
        statusText.text = headline
        statusDetail.text = detail

        permDot.background = dot(color(if (access) R.color.ok else R.color.bad))
        permText.text = if (access) "Notification access granted" else "Notification access missing"

        primary.text = if (relay) "Stop Service" else "Start Service"
        primary.background = filledBackground(
            if (relay) color(R.color.border) else color(R.color.accent)
        )
        primary.setTextColor(color(if (relay) R.color.text_primary else R.color.bg))
        primary.isEnabled = access
        primary.alpha = if (access) 1f else 0.5f

        accessButton.visibility = if (access) View.GONE else View.VISIBLE

        appList.removeAllViews()
        val watched = MessageCollector.loadWatched(this)
        val seen = MessageCollector.loadSeenMessaging(this)
        val likely = likelyMessagingApps()
        val installed = installedApps()
            .filter { watched.contains(it) || seen.contains(it) || likely.contains(it) }
            .sortedBy { labelFor(it).lowercase() }
        // Names, not just counts: when an app you expected is missing, this line is the answer.
        Log.i(TAG, "picker shows " + installed + "; detected as messaging: " + likely)
        if (installed.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "No apps found"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(color(R.color.text_dim))
            })
            return
        }

        // Watched first so switching one off is never a scroll hunt, then the apps most likely to
        // be worth watching: ones actually observed messaging you, then ones that can send texts.
        val groups = listOf(
            "On" to installed.filter { watched.contains(it) },
            "Seen messaging you" to installed.filter { !watched.contains(it) && seen.contains(it) },
            "Looks like a messaging app" to installed.filter {
                !watched.contains(it) && !seen.contains(it)
            }
        )
        for ((heading, packages) in groups) {
            if (packages.isEmpty()) continue
            appList.addView(groupHeading(heading))
            packages.forEach {
                appList.addView(appRow(it, watched.contains(it), seen.contains(it)))
            }
        }
    }

    private fun groupHeading(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setTextColor(color(R.color.text_dim))
        setPadding(0, dp(10), 0, dp(2))
    }

    private fun toggleRelay() {
        val next = !relayEnabled()
        prefs.edit().putBoolean(MessageCollector.KEY_ENABLED, next).apply()
        MessageCollector.instance?.setEnabled(next)
        refresh()
    }

    private fun setWatched(pkg: String, on: Boolean) {
        val next = MessageCollector.loadWatched(this).toMutableSet()
        if (on) next.add(pkg) else next.remove(pkg)
        val listener = MessageCollector.instance
        if (listener != null) {
            listener.setWatched(next)
        } else {
            prefs.edit().putStringSet(MessageCollector.KEY_WATCHED, next).apply()
        }
    }

    private fun setNotifySilent(on: Boolean) {
        val listener = MessageCollector.instance
        if (listener != null) {
            listener.setNotifySilent(on)
        } else {
            prefs.edit().putBoolean(MessageCollector.KEY_NOTIFY_SILENT, on).apply()
        }
    }

    private fun setShowText(on: Boolean) {
        val listener = MessageCollector.instance
        if (listener != null) {
            listener.setShowText(on)
        } else {
            prefs.edit().putBoolean(MessageCollector.KEY_SHOW_TEXT, on).apply()
        }
    }

    private fun relayEnabled(): Boolean = prefs.getBoolean(MessageCollector.KEY_ENABLED, true)

    /**
     * Everything installed that a person could plausibly open, which is as good a proxy for
     * "could message me" as the package manager offers. Requires QUERY_ALL_PACKAGES; without it
     * this returns only what the manifest declared visibility for.
     */
    private fun installedApps(): List<String> = try {
        packageManager.getInstalledApplications(0)
            .map { it.packageName }
            .filter { pkg ->
                pkg != packageName &&
                    !MessageCollector.HIDDEN_PACKAGES.contains(pkg) &&
                    packageManager.getLaunchIntentForPackage(pkg) != null
            }
    } catch (t: Throwable) {
        Log.w(TAG, "package enumeration failed", t)
        MessageCollector.loadWatched(this).toList()
    }

    /**
     * Apps that can plausibly deliver a message, worked out before any of them has posted one.
     * Four independent signals, any one sufficient:
     *
     *   1. notification channels that look like a messenger's - the strongest, read from the bound
     *      listener (see MessageCollector.channelsSuggestMessaging);
     *   2. CATEGORY_APP_MESSAGING, the app declaring itself a messaging app in its manifest;
     *   3. registered to send SMS/MMS;
     *   4. named in MessageCollector.KNOWN_MESSENGERS - because signals 1-3 verifiably miss
     *      WhatsApp, Signal, Telegram and Discord, none of which declare the category, handle SMS,
     *      or expose conversation ids before their first message.
     *
     * A fourth signal - requesting RECEIVE_SMS/READ_SMS - was tried and dropped: on this phone it
     * pulled in the Play Store and the emergency-alert receiver, both of which hold SMS permissions
     * for one-time codes and broadcasts rather than for messaging.
     *
     * None of these prove the app posts standard message notifications; only seeing one does. That
     * is what makes everything outside Google Messages experimental, and why apps actually observed
     * messaging you are ranked above this list.
     */
    private fun likelyMessagingApps(): Set<String> {
        val result = HashSet<String>()
        result.addAll(smsCapable())
        result.addAll(declaredMessagingApps())
        result.addAll(MessageCollector.KNOWN_MESSENGERS)

        val listener = MessageCollector.instance
        for (pkg in installedApps()) {
            if (result.contains(pkg)) continue
            if (listener?.channelsSuggestMessaging(pkg) == true) result.add(pkg)
        }
        return result
    }

    /** Apps declaring CATEGORY_APP_MESSAGING - a manifest-level "I am a messaging app". */
    private fun declaredMessagingApps(): Set<String> = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)
        packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    } catch (t: Throwable) {
        emptySet()
    }

    /**
     * Apps registered to send SMS/MMS. No longer the whole list - just one signal among several.
     */
    private fun smsCapable(): Set<String> {
        val result = HashSet<String>()
        try {
            Telephony.Sms.getDefaultSmsPackage(this)?.let { result.add(it) }
        } catch (_: Throwable) {
        }
        for (scheme in listOf("smsto:", "mmsto:")) {
            val sendTo = Intent(Intent.ACTION_SENDTO, Uri.parse(scheme))
            for (ri in packageManager.queryIntentActivities(sendTo, 0)) {
                ri.activityInfo?.packageName?.let { result.add(it) }
            }
        }
        return result
    }

    // The list went from a handful of SMS apps to everything installed, and refresh() rebuilds it
    // on every resume - so resolve each label and icon once.
    private val labelCache = HashMap<String, String>()
    private val iconCache = HashMap<String, Drawable?>()

    private fun labelFor(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    private fun iconFor(pkg: String): Drawable? = iconCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    // ---------------------------------------------------------------- widgets

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            setColor(color(R.color.card))
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), color(R.color.border))
        }
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun filledButton(onClick: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        setOnClickListener { if (isEnabled) onClick() }
    }

    private fun outlineButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(color(R.color.text_primary))
        background = ripple(GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), color(R.color.border))
        })
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun filledBackground(fill: Int): Drawable = ripple(GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(10).toFloat()
    })

    private fun ripple(base: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), base, null)

    /**
     * Both switch states get explicit colors: the stock Material track is a low-alpha gray that
     * disappears against this card, and a single tint makes on and off look identical.
     */
    private fun tintSwitch(sw: Switch) {
        // This build's theme draws "ON"/"OFF" inside the track; the rows already say what they do.
        sw.showText = false
        sw.textOn = ""
        sw.textOff = ""
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(
            states, intArrayOf(color(R.color.accent), color(R.color.switch_thumb_off))
        )
        sw.trackTintList = ColorStateList(
            states, intArrayOf(color(R.color.accent), color(R.color.switch_track_off))
        )
        sw.trackTintMode = android.graphics.PorterDuff.Mode.SRC_IN
    }

    private fun dot(fill: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun color(res: Int): Int = resources.getColor(res, theme)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "CoverNotifier/UI"
    }
}
