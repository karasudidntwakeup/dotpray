package dev.karasu.ndotprayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dev.karasu.ndotprayer.R
import dev.karasu.ndotprayer.data.Format
import dev.karasu.ndotprayer.data.Prayer
import dev.karasu.ndotprayer.data.PrayerKind
import dev.karasu.ndotprayer.data.PrayerRepository
import dev.karasu.ndotprayer.data.Snapshot

/**
 * Turns a [Snapshot] into Nothing-styled [RemoteViews]:
 *
 *  - Nothing panel (light or dark, following the system) with a single hairline
 *    border and launcher corners,
 *  - Nothing's NDot55All dot face for every label and number, with letterspaced
 *    caps for labels,
 *  - Nothing Red on the next prayer time and the leading bar block; the next
 *    time appears once, in the strip under the bar.
 */
object WidgetRenderer {

    /** Below these sizes the five prayer columns don't fit — show the compact head. */
    private const val FULL_MIN_WIDTH_DP = 240
    private const val FULL_MIN_HEIGHT_DP = 170

    private const val DEFAULT_WIDTH_DP = 250
    private const val DEFAULT_HEIGHT_DP = 160

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** In the compact widget, below this height the times strip is dropped. */
    private const val COMPACT_TIMES_MIN_HEIGHT_DP = 155

    /** How long the prayer-arrival walk stays on screen. */
    const val ANNOUNCEMENT_DURATION_MS = 6_000L

    private const val REQUEST_REFRESH = 6001

    @Volatile
    private var announcement: Pair<PrayerKind, Long>? = null

    /** Windows between prayers, in the dot face. */
    fun render(
        context: Context,
        appWidgetId: Int,
        snapshot: Snapshot?,
        loading: Boolean = false,
        announce: PrayerKind? = activeAnnouncement(),
    ): RemoteViews {
        val (widthDp, heightDp) = sizeOf(context, appWidgetId)
        val compact = widthDp < FULL_MIN_WIDTH_DP || heightDp < FULL_MIN_HEIGHT_DP
        val layout = if (compact) R.layout.widget_compact else R.layout.widget
        val views = RemoteViews(context.packageName, layout)

        // The panel paints its own corners (launcher radius); the layout's
        // background stays only as the widget-picker preview.
        views.setImageViewBitmap(R.id.panel_bg, PanelBackground.bitmap(context, widthDp, heightDp))
        views.setInt(R.id.root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)
        views.setOnClickPendingIntent(R.id.root, refreshPendingIntent(context))

        val palette = Palette(context)
        val arrival = if (loading) null else announce
        val announcing = arrival != null
        views.setViewVisibility(R.id.loading, if (loading) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.announce, if (announcing) View.VISIBLE else View.GONE)
        views.setViewVisibility(
            R.id.content,
            if (loading || announcing) View.GONE else View.VISIBLE,
        )

        if (loading) {
            views.setTextColor(R.id.loading_text, palette.label)
            return views
        }
        if (arrival != null) {
            views.setTextViewText(R.id.announce_text, arrival.label)
            views.setTextColor(R.id.announce_text, palette.ink)
            return views
        }

        if (snapshot == null) {
            bindPlaceholder(context, views, compact, heightDp, palette)
            return views
        }

        val next = snapshot.next
        val now = System.currentTimeMillis()
        val remaining = next.timeMillis - now
        val hasHours = remaining >= HOUR

        // The host's Chronometer ticks on the elapsedRealtime clock (device
        // uptime), not on wallclock: a raw epoch base makes it count ~500k
        // hours of nonsense. Translate the prayer's wallclock instant into
        // the uptime clock at render time — it stays exact afterwards.
        val base = android.os.SystemClock.elapsedRealtime() + remaining
        views.setChronometerCountDown(R.id.text_countdown, true)
        views.setChronometer(R.id.text_countdown, base, countdownFormat(remaining), true)
        views.setTextViewText(R.id.text_unit, countdownUnit(remaining))
        views.setViewVisibility(R.id.text_unit, View.VISIBLE)

        views.setTextViewText(R.id.text_prayer, next.kind.label)
        views.setTextViewText(R.id.text_time, Format.clock(next.timeMillis))

        val showsTimes = !compact || heightDp >= COMPACT_TIMES_MIN_HEIGHT_DP
        // The next time lives under the bar, in the schedule. Only a short
        // compact cell (which drops the strip) repeats it in the hero row.
        views.setViewVisibility(R.id.text_time, if (showsTimes) View.GONE else View.VISIBLE)

        // Bind every colour, not just the stateful ones: the host inflates the
        // layout in its own configuration, and the bitmaps above are painted in
        // ours. Explicit colours keep text and panel on the same side of a
        // light/dark switch (no white-on-white if the two ever disagree).
        views.setTextColor(R.id.label_next, palette.label)
        views.setTextColor(R.id.text_countdown, palette.ink)
        views.setTextColor(R.id.text_unit, palette.ink)
        views.setTextColor(R.id.text_prayer, palette.ink)
        views.setTextColor(R.id.text_time, palette.red)

        if (compact) {
            // Short cells keep it to the hero; taller ones add names and times.
            views.setViewVisibility(R.id.names_row, if (showsTimes) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.times_row, if (showsTimes) View.VISIBLE else View.GONE)
            if (showsTimes) bindCompactSchedule(views, snapshot, next, now, palette)

            // "10:45" is longer than "47"; shrink the number to the cell
            // instead of letting the 2x2 countdown clip. The dot face is
            // about 0.48 em per character at its widest. Reserve room for the
            // smaller unit and its gap.
            val baseSize = if (showsTimes) 42f else 38f
            val numberChars = when {
                remaining >= 10 * HOUR -> 5f
                hasHours -> 4f
                else -> 2f
            }
            val unitWidth = 3f * 14f * 0.48f
            val size = (widthDp - 28f - 4f - unitWidth)
                .div(numberChars * 0.48f)
                .coerceIn(20f, baseSize)
            views.setTextViewTextSize(R.id.text_countdown, TypedValue.COMPLEX_UNIT_SP, size)
            views.setTextViewTextSize(
                R.id.text_unit,
                TypedValue.COMPLEX_UNIT_SP,
                (size * 0.34f).coerceIn(10f, 14f),
            )
        } else {
            views.setTextViewText(R.id.bar_label, "FAJR > FAJR")
            views.setTextColor(R.id.bar_label, palette.label)
            bindFullSchedule(views, snapshot, next, now, palette)
            views.setTextViewTextSize(R.id.text_countdown, TypedValue.COMPLEX_UNIT_SP, 52f)
            views.setTextViewTextSize(R.id.text_unit, TypedValue.COMPLEX_UNIT_SP, 16f)
        }

        // The bar is one rail per prayer column: red on the next prayer, ink
        // on the prayers already behind us, track on the rest.
        val activeIndex = snapshot.today.indexOfFirst { it.kind == next.kind }
        val states = IntArray(SegmentBar.SEGMENTS) { index ->
            val prayer = snapshot.today.getOrNull(index)
            when {
                index == activeIndex -> SegmentBar.ACTIVE
                prayer != null && prayer.timeMillis <= now -> SegmentBar.PAST
                else -> SegmentBar.TRACK
            }
        }
        views.setImageViewBitmap(R.id.bar, SegmentBar.bitmap(context, states))

        return views
    }

    /** The five names and times under the bar; the next prayer is red. */
    private fun bindCompactSchedule(
        views: RemoteViews,
        snapshot: Snapshot,
        next: Prayer,
        now: Long,
        palette: Palette,
    ) {
        snapshot.today.take(SegmentBar.SEGMENTS).forEachIndexed { index, prayer ->
            val shown = displayPrayer(prayer, next)
            val nameId = rowNameIds[index]
            val timeId = rowTimeIds[index]
            views.setTextViewText(nameId, shown.kind.label)
            views.setTextColor(nameId, nameColor(shown, next, now, palette))
            views.setTextViewText(timeId, Format.clock(shown.timeMillis))
            views.setTextColor(timeId, timeColor(shown, next, now, palette))
        }
    }

    /** The full table under the bar, with prayer names and the next time in red. */
    private fun bindFullSchedule(
        views: RemoteViews,
        snapshot: Snapshot,
        next: Prayer,
        now: Long,
        palette: Palette,
    ) {
        snapshot.today.take(SegmentBar.SEGMENTS).forEachIndexed { index, prayer ->
            val shown = displayPrayer(prayer, next)
            val nameId = rowNameIds[index]
            val timeId = rowTimeIds[index]
            views.setTextViewText(nameId, shown.kind.label)
            views.setTextViewText(timeId, Format.clock(shown.timeMillis))
            views.setTextColor(nameId, nameColor(shown, next, now, palette))
            views.setTextColor(timeId, timeColor(shown, next, now, palette))
        }
    }

    /**
     * At night the next prayer is tomorrow's Fajr, which is not in today's
     * list. Light the Fajr column with that upcoming time so the widget never
     * loses Fajr, and the bar marks the column it belongs to.
     */
    private fun displayPrayer(prayer: Prayer, next: Prayer): Prayer =
        if (prayer.kind == next.kind) next else prayer

    private fun nameColor(prayer: Prayer, next: Prayer, now: Long, palette: Palette): Int = when {
        prayer.timeMillis == next.timeMillis -> palette.ink
        prayer.timeMillis <= now -> palette.past
        else -> palette.label
    }

    /** Red marks the next prayer; past is dim, later is grey. */
    private fun timeColor(prayer: Prayer, next: Prayer, now: Long, palette: Palette): Int = when {
        prayer.timeMillis == next.timeMillis -> palette.red
        prayer.timeMillis <= now -> palette.past
        else -> palette.future
    }

    /**
     * The Chronometer renders "MM:SS" (under an hour) or "H:MM:SS", then runs
     * it through Formatter — precision lets us take just the numeric head of
     * that string. The unit is a separate, smaller run. The format is fixed
     * until the next re-render, so the AlarmHelper switch alarms land just
     * after each crossing.
     */
    private fun countdownFormat(remainingMillis: Long): String = when {
        remainingMillis >= 10 * HOUR -> "%1$.5s" // "10:45:00" -> "10:45"
        remainingMillis >= HOUR -> "%1$.4s" // "1:08:48"   -> "1:08"
        else -> "%1$.2s" // "47:59"     -> "47"
    }

    private fun countdownUnit(remainingMillis: Long): String =
        if (remainingMillis >= HOUR) "HRS" else "MIN"

    /**
     * The launcher reports the current cell as min/max bounds in dp (the two
     * bracket the live size; nothing OS sets them to the current cell). Small
     * cells get the compact head.
     */
    private fun sizeOf(context: Context, appWidgetId: Int): Pair<Int, Int> {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val width = maxOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0),
        )
        val height = maxOf(
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0),
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0),
        )
        // Unknown (freshly placed, before the first options pass): assume the default cell.
        return (if (width <= 0) DEFAULT_WIDTH_DP else width) to
            (if (height <= 0) DEFAULT_HEIGHT_DP else height)
    }

    private fun bindPlaceholder(
        context: Context,
        views: RemoteViews,
        compact: Boolean,
        heightDp: Int,
        palette: Palette,
    ) {
        views.setChronometer(R.id.text_countdown, 0L, "--:--", false)
        views.setTextViewText(R.id.text_prayer, "SYNCING")
        views.setTextViewText(R.id.text_time, "--:--")
        views.setImageViewBitmap(R.id.bar, SegmentBar.bitmap(context, null))
        views.setTextColor(R.id.label_next, palette.label)
        views.setTextColor(R.id.text_countdown, palette.label)
        views.setTextColor(R.id.text_unit, palette.ink)
        views.setTextColor(R.id.text_prayer, palette.ink)
        views.setTextColor(R.id.text_time, palette.red)
        views.setViewVisibility(R.id.text_time, View.GONE)
        views.setViewVisibility(R.id.text_unit, View.GONE)

        if (compact) {
            val showTimes = heightDp >= COMPACT_TIMES_MIN_HEIGHT_DP
            views.setViewVisibility(
                R.id.names_row,
                if (showTimes) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.times_row,
                if (showTimes) View.VISIBLE else View.GONE,
            )
        } else {
            views.setTextViewText(R.id.bar_label, "--")
            views.setTextColor(R.id.bar_label, palette.label)
        }
    }

    /** One theme's colours, resolved for the configuration this render runs in. */
    private class Palette(context: Context) {
        val ink = context.getColor(R.color.nt_white)
        val red = context.getColor(R.color.nt_red)
        val label = context.getColor(R.color.nt_grey_label)
        val past = context.getColor(R.color.nt_grey_past)
        val future = context.getColor(R.color.nt_grey_future)
    }

    /** Android resource ids are opaque, so the five columns are listed explicitly. */
    private val rowNameIds = intArrayOf(
        R.id.row_name_0,
        R.id.row_name_1,
        R.id.row_name_2,
        R.id.row_name_3,
        R.id.row_name_4,
    )

    private val rowTimeIds = intArrayOf(
        R.id.row_time_0,
        R.id.row_time_1,
        R.id.row_time_2,
        R.id.row_time_3,
        R.id.row_time_4,
    )

    /** Show the prayer that just arrived, walking across the panel for a moment. */
    fun announce(context: Context, kind: PrayerKind) {
        announcement = kind to (System.currentTimeMillis() + ANNOUNCEMENT_DURATION_MS)
        push(context)
    }

    /** Hide the arrival animation immediately (called by its timer). */
    fun endAnnouncement(context: Context) {
        announcement = null
        push(context)
    }

    /** A tap: replace the content with the dot loading state until the job repaints. */
    fun pushLoading(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, PrayerWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return
        val snapshot = PrayerRepository.snapshot(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, render(context, id, snapshot, loading = true))
        }
    }

    private fun activeAnnouncement(): PrayerKind? =
        announcement?.takeIf { System.currentTimeMillis() < it.second }?.first

    /** The launcher sends this as us, so the receiver can stay unexported. */
    private fun refreshPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RefreshReceiver::class.java)
            .setAction(RefreshReceiver.ACTION_REFRESH)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Repaints every placed widget from the current cache. */
    fun push(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(
            ComponentName(context, PrayerWidgetProvider::class.java),
        )
        if (ids.isEmpty()) return
        val snapshot = PrayerRepository.snapshot(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, render(context, id, snapshot))
        }
    }
}