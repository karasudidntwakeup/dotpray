package dev.karasu.ndotprayer.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dev.karasu.ndotprayer.R
import dev.karasu.ndotprayer.data.Format
import dev.karasu.ndotprayer.data.Prayer
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

    /** Below these sizes the four secondary rows don't fit — show the compact head. */
    private const val FULL_MIN_WIDTH_DP = 240
    private const val FULL_MIN_HEIGHT_DP = 220

    private const val DEFAULT_WIDTH_DP = 250
    private const val DEFAULT_HEIGHT_DP = 160

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** In the compact widget, below this height the times strip is dropped. */
    private const val COMPACT_TIMES_MIN_HEIGHT_DP = 155

    /** Windows between prayers, in the dot face. */
    fun render(context: Context, appWidgetId: Int, snapshot: Snapshot?): RemoteViews {
        val (widthDp, heightDp) = sizeOf(context, appWidgetId)
        val compact = widthDp < FULL_MIN_WIDTH_DP || heightDp < FULL_MIN_HEIGHT_DP
        val layout = if (compact) R.layout.widget_compact else R.layout.widget
        val views = RemoteViews(context.packageName, layout)

        // The panel paints its own corners (launcher radius); the layout's
        // background stays only as the widget-picker preview.
        views.setImageViewBitmap(R.id.panel_bg, PanelBackground.bitmap(context, widthDp, heightDp))
        views.setInt(R.id.root, "setBackgroundColor", android.graphics.Color.TRANSPARENT)

        if (snapshot == null) {
            bindPlaceholder(context, views, compact, heightDp)
            return views
        }

        val palette = Palette(context)
        val next = snapshot.next
        val remaining = next.timeMillis - System.currentTimeMillis()

        // The host's Chronometer ticks on the elapsedRealtime clock (device
        // uptime), not on wallclock: a raw epoch base makes it count ~500k
        // hours of nonsense. Translate the prayer's wallclock instant into
        // the uptime clock at render time — it stays exact afterwards.
        val base = android.os.SystemClock.elapsedRealtime() + remaining
        views.setChronometerCountDown(R.id.text_countdown, true)
        views.setChronometer(R.id.text_countdown, base, countdownFormat(remaining), true)

        views.setTextViewText(R.id.text_prayer, next.kind.label)
        views.setTextViewText(R.id.text_time, Format.clock(next.timeMillis))

        // The next time lives under the bar, in the schedule. Only when the next
        // prayer is tomorrow's Fajr (not in today's list) does it stay in the
        // hero row, so the widget never loses it at night.
        val nextInToday = snapshot.today.any { it.timeMillis == next.timeMillis }
        views.setViewVisibility(R.id.text_time, if (nextInToday) View.GONE else View.VISIBLE)

        // Bind every colour, not just the stateful ones: the host inflates the
        // layout in its own configuration, and the bitmaps above are painted in
        // ours. Explicit colours keep text and panel on the same side of a
        // light/dark switch (no white-on-white if the two ever disagree).
        views.setTextColor(R.id.label_next, palette.label)
        views.setTextColor(R.id.text_countdown, palette.ink)
        views.setTextColor(R.id.text_prayer, palette.ink)
        views.setTextColor(R.id.text_time, palette.red)

        if (compact) {
            // Short cells keep it to the hero; taller ones add all five times.
            val showTimes = heightDp >= COMPACT_TIMES_MIN_HEIGHT_DP
            views.setViewVisibility(R.id.times_row, if (showTimes) View.VISIBLE else View.GONE)
            if (showTimes) bindCompactTimes(views, snapshot, next, palette)

            views.setTextViewTextSize(
                R.id.text_countdown,
                TypedValue.COMPLEX_UNIT_SP,
                if (showTimes) 42f else 38f,
            )
        } else {
            val window = snapshot.previous?.kind?.label
            views.setTextViewText(
                R.id.bar_label,
                if (window != null) "$window > ${next.kind.label}" else next.kind.label,
            )
            views.setTextColor(R.id.bar_label, palette.label)
            bindFullSchedule(views, snapshot, next, palette)
        }

        views.setImageViewBitmap(R.id.bar, SegmentBar.bitmap(context, (snapshot.progress * 100).toInt()))

        return views
    }

    /** The five times under the bar; the next one is the only red time. */
    private fun bindCompactTimes(views: RemoteViews, snapshot: Snapshot, next: Prayer, palette: Palette) {
        val now = System.currentTimeMillis()
        snapshot.today.take(5).forEachIndexed { index, prayer ->
            val timeId = rowTimeIds[index]
            views.setTextViewText(timeId, Format.clock(prayer.timeMillis))
            views.setTextColor(timeId, timeColor(prayer, next, now, palette))
        }
    }

    /** The full table under the bar, with prayer names and the next time in red. */
    private fun bindFullSchedule(views: RemoteViews, snapshot: Snapshot, next: Prayer, palette: Palette) {
        val now = System.currentTimeMillis()
        snapshot.today.take(5).forEachIndexed { index, prayer ->
            val nameId = rowNameIds[index]
            val timeId = rowTimeIds[index]
            views.setTextViewText(nameId, prayer.kind.label)
            views.setTextViewText(timeId, Format.clock(prayer.timeMillis))
            views.setTextColor(
                nameId,
                when {
                    prayer.timeMillis == next.timeMillis -> palette.ink
                    prayer.timeMillis <= now -> palette.past
                    else -> palette.label
                },
            )
            views.setTextColor(timeId, timeColor(prayer, next, now, palette))
        }
    }

    /** Red marks the next prayer; past is dim, later is grey. */
    private fun timeColor(prayer: Prayer, next: Prayer, now: Long, palette: Palette): Int = when {
        prayer.timeMillis == next.timeMillis -> palette.red
        prayer.timeMillis <= now -> palette.past
        else -> palette.future
    }

    /**
     * The Chronometer renders "MM:SS" (under an hour) or "H:MM:SS", then runs
     * it through Formatter — precision lets us take just the wanted head of
     * that string. The format is fixed until the next re-render, so the
     * AlarmHelper switch alarms land just after each crossing.
     */
    private fun countdownFormat(remainingMillis: Long): String = when {
        remainingMillis >= 10 * HOUR -> "%1$.5s" // "10:45:00" -> "10:45"
        remainingMillis >= HOUR -> "%1$.4s" // "1:08:48"   -> "1:08"
        else -> "%1$.2s MIN" // "47:59"     -> "47 MIN"
    }

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

    private fun bindPlaceholder(context: Context, views: RemoteViews, compact: Boolean, heightDp: Int) {
        val palette = Palette(context)
        views.setChronometer(R.id.text_countdown, 0L, "--:--", false)
        views.setTextViewText(R.id.text_prayer, "SYNCING")
        views.setTextViewText(R.id.text_time, "--:--")
        views.setImageViewBitmap(R.id.bar, SegmentBar.bitmap(context, 0))
        views.setTextColor(R.id.label_next, palette.label)
        views.setTextColor(R.id.text_countdown, palette.label)
        views.setTextColor(R.id.text_prayer, palette.ink)
        views.setTextColor(R.id.text_time, palette.red)
        views.setViewVisibility(R.id.text_time, View.GONE)

        if (compact) {
            views.setViewVisibility(
                R.id.times_row,
                if (heightDp >= COMPACT_TIMES_MIN_HEIGHT_DP) View.VISIBLE else View.GONE,
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