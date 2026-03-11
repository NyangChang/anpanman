package com.example.studytimelapse.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Draws a 16×16 pixel-art cat and returns it as a [BitmapDrawable] for osmdroid.
 *
 * Color key:
 *   0 = transparent
 *   1 = orange body      (#FF9800)
 *   2 = dark brown       (#4E342E)  – eyes, stripes
 *   3 = white            (#FFFFFF)  – muzzle
 *   4 = pink             (#F48FB1)  – nose / inner ear
 */
object CatMarkerFactory {

    // 16×16 pixel art cat (sitting, front view)
    private val PIXELS = arrayOf(
        //0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
        intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), // row 0
        intArrayOf(0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0), // row 1  ears
        intArrayOf(0, 1, 4, 1, 1, 0, 0, 0, 0, 1, 1, 4, 1, 0, 0, 0), // row 2  inner ear
        intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0), // row 3  head
        intArrayOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0), // row 4
        intArrayOf(0, 1, 1, 2, 2, 1, 1, 1, 1, 2, 2, 1, 1, 0, 0, 0), // row 5  eyes
        intArrayOf(0, 1, 1, 2, 2, 1, 1, 1, 1, 2, 2, 1, 1, 0, 0, 0), // row 6
        intArrayOf(0, 1, 1, 1, 3, 3, 3, 4, 3, 3, 3, 1, 1, 0, 0, 0), // row 7  muzzle + nose
        intArrayOf(0, 1, 2, 1, 3, 3, 3, 3, 3, 3, 3, 1, 2, 0, 0, 0), // row 8  whiskers
        intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0), // row 9  chin
        intArrayOf(0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0), // row 10 neck
        intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0), // row 11 body
        intArrayOf(0, 0, 1, 1, 2, 1, 1, 1, 1, 2, 1, 1, 0, 0, 0, 0), // row 12 stripes
        intArrayOf(0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0), // row 13
        intArrayOf(0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0), // row 14 paws
        intArrayOf(0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0), // row 15
    )

    private val COLOR = mapOf(
        1 to 0xFFFF9800.toInt(), // orange
        2 to 0xFF4E342E.toInt(), // dark brown
        3 to 0xFFFFFFFF.toInt(), // white
        4 to 0xFFF48FB1.toInt(), // pink
    )

    /**
     * Returns a [Drawable] of the pixel-art cat scaled to [sizeDp] × [sizeDp] dp.
     */
    fun create(context: Context, sizeDp: Int = 48): Drawable {
        val density = context.resources.displayMetrics.density
        val cellPx = (sizeDp * density / PIXELS.size).coerceAtLeast(1f)
        val bitmapSize = (PIXELS.size * cellPx).toInt()

        val bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = false }

        PIXELS.forEachIndexed { row, cols ->
            cols.forEachIndexed { col, colorKey ->
                val color = COLOR[colorKey] ?: return@forEachIndexed
                paint.color = color
                canvas.drawRect(
                    col * cellPx,
                    row * cellPx,
                    (col + 1) * cellPx,
                    (row + 1) * cellPx,
                    paint,
                )
            }
        }

        return BitmapDrawable(context.resources, bitmap)
    }
}
