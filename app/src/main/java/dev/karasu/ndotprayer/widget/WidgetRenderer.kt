package dev.karasu.ndotprayer.widget

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
import dev.karasu.ndotprayer.data.Prefs
import dev.karasu.ndotprayer.data.PrayerRepository
import dev.karasu.ndotprayer.data.Snapshot

/**
 * Turns a [Snapshot] into Nothing-styled [RemoteViews]:
 *
 *  - Nothing panel (light or dark, following the system) with a single hairline
 *    border and launcher corners,
 *  - NDot for the countdown and clock, grey letterspaced caps for labels,
 *  - Nothing Red used exactly once per view (the leading bar block / the active prayer).
 */
object WidgetRenderer {

    /** Below these sizes the five prayer rows don't fit — show the compact head. */
    private const val FULL_MIN_WIDTH_DP = 230
    private const val FULL_MIN_HEIGHT_DP = 160

    private const val DEFAULT_WIDTH_DP = 250
    private const val DEFAULT_HEIGHT_DP = 160

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** In the compact widget, below these heights the extra rows are dropped. */
    private const val COMPACT_TIMES_MIN_HEIGHT_DP = 140
    private const val COMPACT_HIJRI_MIN_HEIGHT_DP = 100

    /** Windows between prayers, in NDot digits. */
    fun render(context: Context, appWidgetId: Int, snapshot: Snapshot?, prefs: Prefs.Store): RemoteViews {
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
        views.setTextColor(R.id.text_countdown, context.getColor(R.color.nt_white))

        val hijri = snapshot.hijri.ifBlank { Format.dayStamp(java.time.LocalDate.now()) }

        if (compact) {
            // Short cells keep it to a skeleton, taller ones fill with detail.
            val showHijri = heightDp >= COMPACT_HIJRI_MIN_HEIGHT_DP
            views.setViewVisibility(R.id.label_meta, if (showHijri) View.VISIBLE else View.GONE)
            if (showHijri) views.setTextViewText(R.id.label_meta, hijri)

            val showTimes = heightDp >= COMPACT_TIMES_MIN_HEIGHT_DP
            views.setViewVisibility(R.id.times_row, if (showTimes) View.VISIBLE else View.GONE)
            if (showTimes) bindTimes(context, views, snapshot, next)

            views.setTextViewTextSize(
                R.id.text_countdown,
                TypedValue.COMPLEX_UNIT_SP,
                when {
                    showTimes -> 24f
                    showHijri -> 20f
                    else -> 16f
                },
            )
        } else {
            views.setTextViewText(R.id.label_meta, hijri)
            views.setTextViewText(R.id.bar_label, snapshot.previous?.kind?.label.orEmpty())

            snapshot.today.forEachIndexed { index, prayer ->
                val nameId = rowNameIds.getOrNull(index) ?: return@forEachIndexed
                val timeId = rowTimeIds.getOrNull(index) ?: return@forEachIndexed
                views.setTextViewText(nameId, prayer.kind.label)
                views.setTextViewText(timeId, Format.clock(prayer.timeMillis))
                views.setTextColor(
                    nameId,
                    when {
                        prayer.kind == next.kind -> context.getColor(R.color.nt_red)
                        prayer.timeMillis <= System.currentTimeMillis() ->
                            context.getColor(R.color.nt_grey_past)
                        else -> context.getColor(R.color.nt_grey_label)
                    },
                )
                views.setTextColor(timeId, timeColor(context, prayer, next))
            }
        }

        views.setImageViewBitmap(R.id.bar, SegmentBar.bitmap(context, (snapshot.progress * 100).toInt()))

        return views
    }

    /** Next prayer is the primary ink, past is dim, upcoming is grey. */
    private fun timeColor(context: Context, prayer: Prayer, next: Prayer): Int = when {
        prayer.kind == next.kind -> context.getColor(R.color.nt_white)
        prayer.timeMillis <= System.currentTimeMillis() -> context.getColor(R.color.nt_grey_past)
        else -> context.getColor(R.color.nt_grey_future)
    }

    private fun bindTimes(context: Context, views: RemoteViews, snapshot: Snapshot, next: Prayer) {
        snapshot.today.forEachIndexed { index, prayer ->
            val timeId = rowTimeIds.getOrNull(index) ?: return@forEachIndexed
            views.setTextViewText(timeId, Format.clock(prayer.timeMillis))
            views.setTextColor(timeId, timeColor(context, prayer, next))
        }
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
        views.setChronometer(R.id.text_countdown, 0L, "--:--", false)
        views.setTextViewText(R.id.text_prayer, "SYNCING")
        views.setTextViewText(R.id.text_time, "--:--")
        views.setImageViewBitmap(R.id.bar, SegmentBar.bitmap(context, 0))
        views.setTextColor(R.id.text_countdown, context.getColor(R.color.nt_grey_label))

        if (compact) {
            views.setViewVisibility(
                R.id.label_meta,
                if (heightDp >= COMPACT_HIJRI_MIN_HEIGHT_DP) View.VISIBLE else View.GONE,
            )
            views.setViewVisibility(
                R.id.times_row,
                if (heightDp >= COMPACT_TIMES_MIN_HEIGHT_DP) View.VISIBLE else View.GONE,
            )
        }
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
        val prefs = Prefs.get(context)
        ids.forEach { id ->
            manager.updateAppWidget(id, render(context, id, snapshot, prefs))
        }
    }
}