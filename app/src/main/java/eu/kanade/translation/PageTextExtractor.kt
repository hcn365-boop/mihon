package eu.kanade.translation

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.TranslationBlock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Which OCR engine reads the page. HYBRID runs both and combines the results. */
enum class OcrMode { MLKIT, PADDLE, HYBRID }

/** A text box found by an OCR engine, in image pixels. */
class OcrBox(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val symWidth: Float = PageTextExtractor.DEFAULT_SYMBOL,
    val symHeight: Float = PageTextExtractor.DEFAULT_SYMBOL,
    val angle: Float = 0f,
)

/**
 * Finds and merges the text of one page. It knows nothing about queues, files or chapters:
 * the caller gives it a bitmap and gets back the page's text blocks (or null if there is no text).
 *
 * The engines are passed as functions so this class does not depend on how they are built:
 * [mlKit] returns the raw ML Kit result, [paddle] returns boxes already converted to [OcrBox].
 * A mode needs its engine: MLKIT needs [mlKit], PADDLE needs [paddle], HYBRID needs both.
 */
class PageTextExtractor(
    private val mode: OcrMode,
    private val isRtl: Boolean,
    private val mlKit: (suspend (InputImage) -> Text)?,
    private val paddle: (suspend (InputImage) -> List<OcrBox>)?,
) {

    suspend fun extract(bitmap: Bitmap): PageTranslation? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val boxes = when (mode) {
            OcrMode.MLKIT -> mlKitBoxes(image)
            OcrMode.PADDLE -> paddleBoxes(image)
            // One after the other, not in parallel: both are heavy and share the CPU and memory.
            OcrMode.HYBRID -> combine(mlKitBoxes(image), paddleBoxes(image))
        }
        if (boxes.isEmpty()) return null

        val page = PageTranslation(imgWidth = bitmap.width.toFloat(), imgHeight = bitmap.height.toFloat())
        page.blocks = TextBlockMerger.merge(boxes.map { it.toBlock() }, isRtl)
        return page.takeIf { it.blocks.isNotEmpty() }
    }

    private suspend fun mlKitBoxes(image: InputImage): List<OcrBox> {
        val recognize = mlKit ?: return emptyList()
        return recognize(image).textBlocks.mapNotNull { block ->
            val bounds = block.boundingBox ?: return@mapNotNull null
            if (block.text.length <= 1) return@mapNotNull null
            val line = block.lines.firstOrNull()
            val symbol = line?.elements?.firstOrNull()?.symbols?.firstOrNull()?.boundingBox
            OcrBox(
                text = block.text,
                x = bounds.left.toFloat(),
                y = bounds.top.toFloat(),
                width = bounds.width().toFloat(),
                height = bounds.height().toFloat(),
                symWidth = symbol?.width()?.toFloat() ?: DEFAULT_SYMBOL,
                symHeight = symbol?.height()?.toFloat() ?: DEFAULT_SYMBOL,
                angle = line?.angle ?: 0f,
            )
        }
    }

    private suspend fun paddleBoxes(image: InputImage): List<OcrBox> {
        val recognize = paddle ?: return emptyList()
        return recognize(image).filter { it.text.isNotBlank() }
    }

    /**
     * ML Kit is the base: its boxes carry the symbol size and angle the merging needs.
     * Paddle adds boxes ML Kit missed. Where both found the same box, Paddle's text replaces
     * ML Kit's only when it is clearly longer (ML Kit often drops characters in vertical text).
     */
    private fun combine(primary: List<OcrBox>, secondary: List<OcrBox>): List<OcrBox> {
        val result = primary.toMutableList()
        for (box in secondary) {
            val index = result.indexOfFirst { overlapRatio(it, box) > OVERLAP_SAME_BOX }
            if (index < 0) {
                result.add(box)
                continue
            }
            val old = result[index]
            if (box.text.length >= old.text.length * LONGER_TEXT_FACTOR) {
                result[index] = OcrBox(
                    text = box.text,
                    x = old.x,
                    y = old.y,
                    width = old.width,
                    height = old.height,
                    symWidth = old.symWidth,
                    symHeight = old.symHeight,
                    angle = old.angle,
                )
            }
        }
        return result
    }

    /** Overlapping area divided by the area of the smaller box. */
    private fun overlapRatio(a: OcrBox, b: OcrBox): Float {
        val width = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
        val height = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
        if (width <= 0f || height <= 0f) return 0f
        val smaller = min(a.width * a.height, b.width * b.height)
        return if (smaller <= 0f) 0f else width * height / smaller
    }

    private fun OcrBox.toBlock() = TranslationBlock(
        text = text,
        width = width,
        height = height,
        symWidth = symWidth,
        symHeight = symHeight,
        angle = angle,
        x = x,
        y = y,
    )

    companion object {
        const val DEFAULT_SYMBOL = 15f
        private const val OVERLAP_SAME_BOX = 0.5f
        private const val LONGER_TEXT_FACTOR = 1.5f

        /** Languages read right to left or vertically, where the reading order of boxes is reversed. */
        fun isRtlLanguage(language: String): Boolean = language.lowercase().let {
            it.contains("ja") || it.contains("ko") || it.contains("zh") || it.contains("ar")
        }
    }
}

/** Joins nearby text boxes of one speech bubble into a single block, in reading order. */
internal object TextBlockMerger {

    fun merge(blocks: List<TranslationBlock>, isRtl: Boolean): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val clusters = mutableListOf<MutableList<TranslationBlock>>()
        val unmerged = blocks.toMutableList()
        while (unmerged.isNotEmpty()) {
            val cluster = mutableListOf(unmerged.removeAt(0))
            do {
                var grew = false
                val iterator = unmerged.iterator()
                while (iterator.hasNext()) {
                    val next = iterator.next()
                    if (cluster.any { shouldMerge(it, next) }) {
                        cluster.add(next)
                        iterator.remove()
                        grew = true
                    }
                }
            } while (grew)
            clusters.add(cluster)
        }

        return clusters.map { cluster ->
            // Manga: top to bottom, right to left. Other languages: top to bottom, left to right.
            val sorted = if (isRtl) {
                cluster.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            } else {
                cluster.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            }
            sorted.drop(1).fold(sorted.first()) { merged, next -> join(merged, next, isRtl) }
        }.toMutableList()
    }

    private fun shouldMerge(a: TranslationBlock, b: TranslationBlock): Boolean {
        val verticalMargin = max(a.symHeight, b.symHeight) * 1.2f
        val horizontalMargin = max(a.symHeight, b.symHeight) * 1.5f
        val overlapsX = b.x <= a.x + a.width + horizontalMargin && b.x + b.width >= a.x - horizontalMargin
        val overlapsY = b.y <= a.y + a.height + verticalMargin && b.y + b.height >= a.y - verticalMargin
        return overlapsX && overlapsY && abs(a.angle - b.angle) < 10f
    }

    private fun join(top: TranslationBlock, bottom: TranslationBlock, isRtl: Boolean): TranslationBlock {
        val x = min(top.x, bottom.x)
        val y = min(top.y, bottom.y)
        val symHeight = (top.symHeight + bottom.symHeight) / 2

        // On the same line the horizontal order decides; on different lines the upper block reads first.
        val sameLine = abs(top.y - bottom.y) < symHeight
        val topFirst = !sameLine || if (isRtl) top.x > bottom.x else top.x < bottom.x
        fun ordered(first: String, second: String) = if (topFirst) "$first $second" else "$second $first"

        val translation = if (top.translation.isNotEmpty() && bottom.translation.isNotEmpty()) {
            ordered(top.translation, bottom.translation)
        } else {
            top.translation + bottom.translation
        }

        return TranslationBlock(
            text = ordered(top.text, bottom.text),
            translation = translation,
            width = max(top.x + top.width, bottom.x + bottom.width) - x,
            height = max(top.y + top.height, bottom.y + bottom.height) - y,
            x = x,
            y = y,
            symHeight = symHeight,
            symWidth = (top.symWidth + bottom.symWidth) / 2,
            angle = (top.angle + bottom.angle) / 2,
        )
    }
}
