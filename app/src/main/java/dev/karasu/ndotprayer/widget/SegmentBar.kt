package dev.karasu.ndotprayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.karasu.ndotprayer.R

/**
 * The prayer bar, painted to a bitmap.
 *
 * RemoteViews may not inflate custom views, so the bar travels to the widget
 * host as pixels instead: [WidgetRenderer] hands it to an ImageView.
 *
 * It is five rails, one per prayer column, not an elapsed-time fill. The rail
 * above the next prayer is Nothing Red, the rails already behind it use the
 * theme ink, and the rest stay on the track colour. That keeps the marker on
 * the same line as the prayer names and times below it.
 */
object SegmentBar {

    /** One rail per prayer, matching the five columns in both layouts. */
    const val SEGMENTS = 5

    const val TRACK = 0
    const val PAST = 1
    const val ACTIVE = 2

    private const val HEIGHT_DP = 4f
    private const val GAP_DP = 6f

    /** Width the host will receive; scales to the cell without distortion notice. */
    private const val DEFAULT_WIDTH_DP = 250f

    fun bitmap(context: Context, states: IntArray?): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (DEFAULT_WIDTH_DP * density).toInt()
        val heightPx = (HEIGHT_DP * density).coerceAtLeast(1f).toInt()
        val gapPx = (GAP_DP * density).coerceAtLeast(1f)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val ink = context.getColor(R.color.nt_white)
        val red = context.getColor(R.color.nt_red)
        val track = context.getColor(R.color.nt_bar_track)

        // Centre each rail in its own column. Inset both ends by half the gap,
        // otherwise every rail sits half a gap to the left of the prayer name
        // it describes. The launcher may stretch the bitmap, so the centres
        // still match the five equal-width text columns.
        val column = widthPx.toFloat() / SEGMENTS
        val inset = gapPx / 2f

        repeat(SEGMENTS) { index ->
            paint.color = when (states?.getOrNull(index)) {
                ACTIVE -> red
                PAST -> ink
                else -> track
            }
            val startX = index * column + inset
            canvas.drawRect(startX, 0f, startX + column - gapPx, heightPx.toFloat(), paint)
        }
        return bitmap
    }
}
