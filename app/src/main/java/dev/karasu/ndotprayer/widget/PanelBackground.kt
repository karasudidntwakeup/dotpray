package dev.karasu.ndotprayer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.karasu.ndotprayer.R

/**
 * The panel, painted to a bitmap so the corner radius matches the enclosing
 * launcher exactly. Colours come from the theme resources, so light mode gets
 * the light panel and dark mode the near-black one.
 *
 * The Nothing launcher rounds widget corners itself via
 * `widget_radius_for_other_in_columns_4` (20dp, multi-cell) and
 * `widget_radius_for_1_x_in_columns_4` (14dp, one cell tall) — values read
 * from NothingLauncher3.apk on the device. RemoteViews can't reference the
 * launcher's private dimens, so the same numbers live here.
 */
object PanelBackground {

    private const val RADIUS_MULTI_CELL_DP = 20f
    private const val RADIUS_ONE_CELL_TALL_DP = 14f

    /** Below this height a widget is a single launcher cell. */
    private const val ONE_CELL_TALL_DP = 110

    /** Supersampling factor; the host stretches the bitmap to the cell anyway. */
    private const val SCALE = 3f

    fun bitmap(context: Context, widthDp: Int, heightDp: Int): Bitmap {
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp.coerceAtLeast(1) * SCALE).toInt()
        val heightPx = (heightDp.coerceAtLeast(1) * SCALE).toInt()
        val radiusPx = radiusDp(heightDp) * SCALE
        val strokePx = 1f * SCALE * density.coerceAtLeast(1f)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val inset = strokePx / 2f
        val rect = RectF(inset, inset, widthPx - inset, heightPx - inset)

        paint.color = context.getColor(R.color.nt_panel)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokePx
        paint.color = context.getColor(R.color.nt_hairline)
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        return bitmap
    }

    private fun radiusDp(heightDp: Int): Float =
        if (heightDp < ONE_CELL_TALL_DP) RADIUS_ONE_CELL_TALL_DP else RADIUS_MULTI_CELL_DP
}
