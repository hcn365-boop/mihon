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
            OcrMode.HYBRID -> combine(mlKitBoxes(image), paddleBoxes(image))
        }
        if (boxes.isEmpty()) return null

        val page = PageTranslation(imgWidth = bitmap.width.toFloat(), imgHeight = bitmap.height.toFloat())
        // استخدام خوارزمية الدمج المتقدمة المستفادة من ChapterTranslator بدون حل التداخل
        page.blocks = TextBlockMerger.merge(boxes.map { it.toBlock() }, bitmap.width.toFloat(), bitmap.height.toFloat())
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

        fun isRtlLanguage(language: String): Boolean = language.lowercase().let {
            it.contains("ja") || it.contains("ko") || it.contains("zh") || it.contains("ar")
        }
    }
}

/** Joins nearby text boxes using advanced ChapterTranslator merging logic without collision resolution. */
internal object TextBlockMerger {

    fun merge(blocks: List<TranslationBlock>, imgWidth: Float, imgHeight: Float): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val filteredBlocks = blocks.filter { it.text.isNotBlank() }
        val isWebtoon = imgHeight > 2300f || imgHeight > (imgWidth * 2f)
        val initialBlocks = filteredBlocks.toMutableList()

        val xThreshold = (2.5f * (imgWidth / 1200f).coerceAtMost(3.5f)).coerceAtLeast(1.0f)
        val yThresholdFactor = (1.6f * (imgHeight / 2000f).coerceAtMost(2.6f)).coerceAtLeast(1.0f)

        var i = 0
        while (i < initialBlocks.size) {
            var j = i + 1
            var merged = false
            while (j < initialBlocks.size) {
                if (shouldMergeTextBlock(initialBlocks[i], initialBlocks[j], xThreshold, yThresholdFactor)) {
                    initialBlocks[i] = mergeTextBlock(initialBlocks[i], initialBlocks[j], isWebtoon)
                    initialBlocks.removeAt(j)
                    i = 0
                    merged = true
                    break
                }
                j++
            }
            if (!merged) i++
        }

        return initialBlocks
    }

    private fun shouldMergeTextBlock(
        r1: TranslationBlock, r2: TranslationBlock,
        xThreshold: Float, yThresholdFactor: Float,
    ): Boolean {
        val angleDiff = abs(r1.angle - r2.angle)
        val angleSimilar = angleDiff < 15 || abs(angleDiff - 180) < 15
        if (!angleSimilar) return false

        val minSymWidth = min(r1.symWidth, r2.symWidth)
        val maxSymWidth = max(r1.symWidth, r2.symWidth)
        val minSymHeight = min(r1.symHeight, r2.symHeight)
        val maxSymHeight = max(r1.symHeight, r2.symHeight)
        val sizeRatioLimit = 3f
        if (maxSymWidth / minSymWidth > sizeRatioLimit || maxSymHeight / minSymHeight > sizeRatioLimit) return false

        val sH = max(minSymHeight, 12f)
        val sW = max(minSymWidth, 12f)
        val maxAllowedGapX = sW * 1.2f
        val maxAllowedGapY = sH * 1.2f
        val isVertical = abs(r1.angle) in 70.0..110.0

        val r1Right = r1.x + r1.width; val r1Bottom = r1.y + r1.height
        val r2Right = r2.x + r2.width; val r2Bottom = r2.y + r2.height

        val yOverlap = max(0f, min(r1Bottom, r2Bottom) - max(r1.y, r2.y))
        val xOverlap = max(0f, min(r1Right, r2Right) - max(r1.x, r2.x))

        val minHeight = min(r1.height, r2.height)
        val isFullVerticalCover = yOverlap >= (minHeight * 0.75f)
        val minWidth = min(r1.width, r2.width)
        val isFullHorizontalCover = xOverlap >= (minWidth * 0.95f)
        val isInCross = isFullHorizontalCover || isFullVerticalCover
        if (!isInCross) return false

        if (isVertical) {
            val sideGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
            if (sideGap > maxAllowedGapX) return false
            if (isFullVerticalCover && sideGap <= 0f) return true
            val vertGap = if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom
            if (vertGap > maxAllowedGapY) return false
            val isTouchingOrClose = sideGap <= (sW * 1.2f) && vertGap <= (sH * 1.2f)
            if (!isTouchingOrClose) return false
            val dy = abs(r1.y - r2.y); val dx = abs(r1.x - r2.x)
            val isOriginsClose = dy < (sH * 1.2f) && dx < (sW * 1.2f)
            val isSideBySide = sideGap < (sW * 1.2f) && dy < (sH * 1.2f)
            val alignedVertically = yOverlap > (sH * 0.15f)
            val closeHorizontally = sideGap < (sW * 1.2f)
            return isOriginsClose || isSideBySide || (closeHorizontally && alignedVertically)
        } else {
            val vGap = max(0f, if (r1.y < r2.y) r2.y - r1Bottom else r1.y - r2Bottom)
            if (vGap > maxAllowedGapY) return false
            if (isFullHorizontalCover && vGap <= maxAllowedGapY) return true
            val sideGap = if (r1.x < r2.x) r2.x - r1Right else r1.x - r2Right
            if (sideGap > maxAllowedGapX) return false
            val hasHighSideOverlap = xOverlap > (minWidth * 0.70f)
            val centerR1X = r1.x + r1.width / 2f; val centerR2X = r2.x + r2.width / 2f
            val centersAligned = abs(centerR1X - centerR2X) < max(r1.width, r2.width) * 0.35f
            val isTouching = vGap <= maxAllowedGapY
            return (hasHighSideOverlap || centersAligned) && isTouching
        }
    }

    private fun mergeTextBlock(a: TranslationBlock, b: TranslationBlock, isWebtoon: Boolean): TranslationBlock {
        val minX = min(a.x, b.x); val minY = min(a.y, b.y)
        val maxX = max(a.x + a.width, b.x + b.width); val maxY = max(a.y + a.height, b.y + b.height)
        val isVertical = abs(a.angle) in 70.0..110.0
        val ordered = if (isVertical) {
            if (a.x > b.x) listOf(a, b) else listOf(b, a)
        } else {
            if (abs(a.y - b.y) > max(a.symHeight, b.symHeight) * 0.5f) {
                if (a.y < b.y) listOf(a, b) else listOf(b, a)
            } else {
                if (isWebtoon) { if (a.x < b.x) listOf(a, b) else listOf(b, a) }
                else { if (a.x > b.x) listOf(a, b) else listOf(b, a) }
            }
        }
        val totalLen = (a.text.length + b.text.length).coerceAtLeast(1)
        return TranslationBlock(
            text = ordered.joinToString(" ") { it.text.trim() },
            translation = ordered.joinToString(" ") { it.translation.trim() }.trim(),
            width = maxX - minX, height = maxY - minY, x = minX, y = minY,
            angle = if (a.text.length >= b.text.length) a.angle else b.angle,
            symWidth = (a.symWidth * a.text.length + b.symWidth * b.text.length) / totalLen,
            symHeight = (a.symHeight * a.text.length + b.symHeight * b.text.length) / totalLen,
        )
    }
}
