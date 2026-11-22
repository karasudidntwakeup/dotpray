package dev.karasu.ndotprayer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import dev.karasu.ndotprayer.R
import dev.karasu.ndotprayer.data.Format
import dev.karasu.ndotprayer.data.PrayerKind
import dev.karasu.ndotprayer.data.PrayerRepository
import dev.karasu.ndotprayer.data.Snapshot
import dev.karasu.ndotprayer.util.ThemeJob

object WidgetRenderer {

    private const val HOUR = 3_600_000L
    private const val DEFAULT_WIDTH = 250f
    private const val DEFAULT_HEIGHT = 160f

    private val names = intArrayOf(
        R.id.row_name_0, R.id.row_name_1, R.id.row_name_2, R.id.row_name_3, R.id.row_name_4,
    )
    private val times = intArrayOf(
        R.id.row_time_0, R.id.row_time_1, R.id.row_time_2, R.id.row_time_3, R.id.row_time_4,
    )
    private val rails = intArrayOf(R.id.rail_0, R.id.rail_1, R.id.rail_2, R.id.rail_3, R.id.rail_4)

    fun render(
        context: Context,
        appWidgetId: Int,
        snapshot: Snapshot?,
        loading: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): RemoteViews {
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
        val tap = tapIntent(context)
        val theme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context else ThemeJob.themed(context)
        val base = snapshot?.next?.let { SystemClock.elapsedRealtime() + it.timeMillis - now }
        fun at(size: SizeF) = bind(theme, size, snapshot, loading, now, base, tap)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sizes = widgetSizes(options).filter { it.width > 0 && it.height > 0 }.distinct()
            if (sizes.size in 2..16) return RemoteViews(sizes.associateWith(::at))
            if (sizes.size == 1) return at(sizes.single())
        }

        fun dimension(key: String, fallback: Float): Float =
            options.getInt(key).takeIf { it > 0 }?.toFloat() ?: fallback

        val portrait = SizeF(
            dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, DEFAULT_WIDTH),
            dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, DEFAULT_HEIGHT),
        )
        val landscape = SizeF(
            dimension(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, portrait.width),
            dimension(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, portrait.height),
        )
        return if (portrait == landscape) at(portrait) else RemoteViews(at(landscape), at(portrait))
    }

    @android.annotation.SuppressLint("InlinedApi") // Only called on Android 12+.
    @Suppress("DEPRECATION")
    private fun widgetSizes(options: Bundle): List<SizeF> {
        val key = AppWidgetManager.OPTION_APPWIDGET_SIZES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            options.getParcelableArrayList(key, SizeF::class.java).orEmpty()
        } else {
            options.getParcelableArrayList<SizeF>(key).orEmpty()
        }
    }

    private fun bind(
        context: Context,
        size: SizeF,
        snapshot: Snapshot?,
        loading: Boolean,
        now: Long,
        base: Long?,
        tap: PendingIntent,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.widget).apply {
        val short = size.height < 110f
        val narrow = size.width < 155f
        val showSchedule = size.width >= 140f && size.height >= 140f
        val wide = size.width >= 230f
        val next = snapshot?.next?.takeIf { it.timeMillis > now }
        val remaining = next?.let { it.timeMillis - now } ?: 0L

        setImageViewResource(
            R.id.panel,
            if (short) R.drawable.widget_bg_short else R.drawable.widget_bg_solid,
        )
        setOnClickPendingIntent(R.id.root, tap)
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density + 0.5f).toInt()
        val horizontal = px(if (wide && !short) 16 else 12)
        val top = px(if (short) 7 else 12)
        val bottom = px(if (short) 8 else 12)
        setViewPadding(R.id.content, horizontal, top, horizontal, bottom)
        visible(R.id.loading_spinner, loading)
        visible(R.id.dot, !loading)
        visible(R.id.label_next, !narrow && next != null)
        setTextViewText(R.id.label_next, context.getText(if (loading) R.string.label_syncing else R.string.label_next))
        setTextViewText(
            R.id.text_prayer,
            next?.kind?.label ?: context.getText(if (loading) R.string.label_syncing else R.string.label_unavailable),
        )
        visible(R.id.text_time, !showSchedule && next != null)
        setTextViewText(R.id.text_time, next?.let { Format.clock(it.timeMillis) })
        visible(R.id.schedule, showSchedule)

        visible(R.id.countdown_empty, next == null)
        visible(R.id.text_countdown, next != null)
        visible(R.id.text_unit, next != null)
        if (next != null && base != null) {
            // Chronometer formats elapsed time in the launcher without an app wakeup.
            val format = when {
                remaining >= 10 * HOUR -> "%1$.5s"
                remaining >= HOUR -> "%1$.4s"
                else -> "%1$.2s"
            }
            setChronometerCountDown(R.id.text_countdown, true)
            setChronometer(R.id.text_countdown, base, format, true)
            setTextViewText(R.id.text_unit, context.getText(if (remaining >= HOUR) R.string.unit_hrs else R.string.unit_min))
        } else {
            setChronometer(R.id.text_countdown, 0L, null, false)
        }

        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val widthLimit = (size.width - 52f) / (3.15f * fontScale)
        val heightLimit = (size.height - if (showSchedule) 70f else 32f) / fontScale
        val countdownSize = minOf(widthLimit, heightLimit, if (wide) 52f else 38f).coerceAtLeast(14f)
        for (id in intArrayOf(R.id.text_countdown, R.id.countdown_empty)) fontSize(id, countdownSize)
        fontSize(R.id.text_unit, (countdownSize * 0.32f).coerceIn(8f, 15f))
        fontSize(R.id.text_prayer, if (narrow) 9f else 12f)
        fontSize(R.id.text_time, if (narrow) 9f else 12f)

        for (id in intArrayOf(R.id.text_prayer, R.id.text_countdown, R.id.text_unit)) {
            color(context, id, R.color.nt_white)
        }
        color(context, R.id.label_next, R.color.nt_grey_label)
        color(context, R.id.countdown_empty, R.color.nt_grey_label)
        color(context, R.id.text_time, R.color.nt_red)

        PrayerKind.entries.forEachIndexed { index, kind ->
            val active = next?.kind == kind
            val prayer = if (active) next else snapshot?.today?.firstOrNull { it.kind == kind }
            val past = !active && prayer != null && prayer.timeMillis <= now
            val rail = when {
                active -> R.drawable.rail_active
                past -> R.drawable.rail_past
                else -> R.drawable.rail_track
            }
            setImageViewResource(rails[index], rail)
            if (showSchedule) {
                setTextViewText(times[index], prayer?.let { Format.clock(it.timeMillis) }
                    ?: context.getText(R.string.countdown_placeholder))
                fontSize(names[index], if (wide) 9f else 7f)
                fontSize(times[index], if (wide) 12f else 9f)
                color(context, names[index], if (active) R.color.nt_white else R.color.nt_grey_label)
                color(context, times[index], when {
                    active -> R.color.nt_red
                    past -> R.color.nt_grey_past
                    else -> R.color.nt_white
                })
            }
        }
    }

    private fun RemoteViews.visible(id: Int, visible: Boolean) {
        setViewVisibility(id, if (visible) View.VISIBLE else View.GONE)
    }

    private fun RemoteViews.fontSize(id: Int, size: Float) {
        setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun RemoteViews.color(context: Context, id: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setColor(id, "setTextColor", color)
        else setTextColor(id, context.getColor(color))
    }

    fun push(context: Context, loading: Boolean = false) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PrayerWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        val snapshot = PrayerRepository.snapshot(context, now)
        ids.forEach { id ->
            manager.updateAppWidget(id, render(context, id, snapshot, loading, now))
        }
    }

    private fun tapIntent(context: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (NotificationPermissionActivity.needsPrompt(context)) {
            val intent = Intent(context, NotificationPermissionActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            PendingIntent.getActivity(context, 6003, intent, flags)
        } else {
            val intent = Intent(context, RefreshReceiver::class.java).setAction(RefreshReceiver.ACTION_REFRESH)
            PendingIntent.getBroadcast(context, 6001, intent, flags)
        }
    }
}
