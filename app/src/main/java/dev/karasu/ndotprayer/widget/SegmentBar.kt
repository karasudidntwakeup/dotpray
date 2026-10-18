package dev.karasu.ndotprayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * The segmented progress bar, painted to a bitmap.
 *
 * RemoteViews may not inflate custom views, so the bar travels to the widget
 * host as pixels instead: [WidgetRenderer] hands it to an ImageView.
 *
 * Filled blocks are white, the leading filled block is Nothing Red, the
 * unfilled track stays in the panel's near-black grey.
 */
object SegmentBar {

    private const val SEGMENTS = 28
    private const val HEIGHT_DP = 4f
    private const val GAP_DP = 1f

    private const val RED = 0xFFFF3030.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val TRACK = 0xFF262626.toInt()

    /** Width the host will receive; scales to the cell without distortion notice. */
    private const val DEFAULT_WIDTH_DP = 250f

    fun bitmap(context: Context, percent: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (DEFAULT_WIDTH_DP * density).toInt()
        val heightPx = (HEIGHT_DP * density).coerceAtLeast(1f).toInt()
        val gapPx = (GAP_DP * density * 0.75f).coerceAtLeast(1f)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val segmentWidth = (widthPx - (SEGMENTS - 1) * gapPx).toFloat() / SEGMENTS
        val filled = ((percent.coerceIn(0, 100) / 100f) * SEGMENTS).toInt()

        repeat(SEGMENTS) { index ->
            paint.color = when {
                index < filled - 1 -> WHITE
                index == filled - 1 -> RED
                else -> TRACK
            }
            val startX = index * (segmentWidth + gapPx)
            canvas.drawRect(startX, 0f, startX + segmentWidth, heightPx.toFloat(), paint)
        }
        return bitmap
    }
}
