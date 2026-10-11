package eu.kanade.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the translation of a page into its bitmap, so the page can be cached and shown by any viewer
 * with no overlay. It follows the layout of the overlay block of the original app: the text box is
 * the OCR box with a margin of one and two symbol sizes, the font is the largest that fits the box,
 * the text is centered, and boxes tilted more than 85 degrees (vertical text) are not rotated.
 */
object PageTranslationRenderer {

    private const val MIN_BOX_PX = 6f
    private const val MIN_TEXT_PX = 8
    private const val MAX_TEXT_PX = 200

    /** Blocks whose box is tilted more than this are treated as vertical and drawn straight. */
    private const val VERTICAL_ANGLE = 85f

    /**
     * @param source the page (can be larger than the page that was read, for example after upscaling:
     * the boxes are scaled to fit). It is not modified and not recycled; the caller owns it.
     * @return a new bitmap with the translations drawn, or [source] itself if there is nothing to draw
     */
    fun render(source: Bitmap, page: PageTranslation, typeface: Typeface = Typeface.DEFAULT): Bitmap {
        if (page.imgWidth <= 0f || page.blocks.none { it.translation.isNotBlank() }) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        val canvas = Canvas(output)
        val scale = output.width / page.imgWidth
        for (block in page.blocks) {
            // A block without a translation keeps its original text.
            if (block.translation.isNotBlank()) drawBlock(output, canvas, block, scale, typeface)
        }
        return output
    }

    private fun drawBlock(bitmap: Bitmap, canvas: Canvas, block: TranslationBlock, scale: Float, typeface: Typeface) {
        val padX = block.symWidth * 2
        val padY = block.symHeight
        val left = max((block.x - padX / 2) * scale, 0f)
        val top = max((block.y - padY / 2) * scale, 0f)
        val right = min(left + (block.width + padX) * scale, bitmap.width.toFloat())
        val bottom = min(top + (block.height + padY) * scale, bitmap.height.toFloat())
        val width = right - left
        val height = bottom - top
        if (width < MIN_BOX_PX || height < MIN_BOX_PX) return

        // Cover the original text with the color of the page around the box.
        val background = sampleBackground(bitmap, left, top, right, bottom)
        val radius = min(width, height) * 0.15f
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })

        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            color = if (luminance(background) > 0.5f) Color.BLACK else Color.WHITE
        }
        val layout = fitText(block.translation, paint, width.toInt(), height.toInt())

        canvas.save()
        canvas.translate(left + width / 2, top + height / 2)
        if (block.angle <= VERTICAL_ANGLE) canvas.rotate(block.angle)
        canvas.translate(-layout.width / 2f, -layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** Binary search for the largest font size whose wrapped text fits the box height. */
    private fun fitText(text: String, paint: TextPaint, width: Int, height: Int): StaticLayout {
        var low = MIN_TEXT_PX
        var high = max(low, min(height, MAX_TEXT_PX))
        var best = low
        while (low <= high) {
            val mid = (low + high) / 2
            paint.textSize = mid.toFloat()
            if (layout(text, paint, width).height <= height) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        paint.textSize = best.toFloat()
        return layout(text, paint, width)
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()

    /**
     * The color just outside the box: the median of points around it, so a black panel line
     * crossing a few of them does not change the result.
     */
    private fun sampleBackground(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Int {
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()

        fun add(x: Float, y: Float) {
            val pixel = bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1), y.toInt().coerceIn(0, bitmap.height - 1))
            reds.add(Color.red(pixel))
            greens.add(Color.green(pixel))
            blues.add(Color.blue(pixel))
        }

        val steps = 16
        for (i in 0..steps) {
            val x = left + (right - left) * i / steps
            val y = top + (bottom - top) * i / steps
            add(x, top - 2)
            add(x, bottom + 2)
            add(left - 2, y)
            add(right + 2, y)
        }
        return Color.rgb(median(reds), median(greens), median(blues))
    }

    private fun median(values: MutableList<Int>): Int {
        values.sort()
        return values[values.size / 2]
    }

    private fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f
}
