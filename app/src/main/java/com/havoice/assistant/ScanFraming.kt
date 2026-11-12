package com.havoice.assistant

/**
 * Where a barcode must be to count: wholly inside the scan target, the corner-marked box in the
 * middle of the preview. A barcode cut off by the edge of the view can still decode, wrongly, and
 * keep decoding the same wrong way frame after frame; requiring it inside the target means it's
 * never near an edge, and the corners show exactly what counts.
 *
 * The camera frame holds more than the preview shows (the preview is cropped to fill its space),
 * so the target is worked out from the visible area, not the whole frame.
 */
object ScanFraming {
    /** Left, top, right, bottom, in pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The target's width, as a share of the visible width. */
    const val TARGET_WIDTH = 0.8

    /** Its height: barcodes are wide, so at most this share of its width, and of the visible height. */
    const val TARGET_HEIGHT_OF_WIDTH = 0.6
    const val TARGET_MAX_HEIGHT = 0.8

    /** The scan target within [visible], centered. */
    fun target(visible: Box): Box {
        val w = (visible.width * TARGET_WIDTH).toInt()
        val h = minOf(w * TARGET_HEIGHT_OF_WIDTH, visible.height * TARGET_MAX_HEIGHT).toInt()
        val left = visible.left + (visible.width - w) / 2
        val top = visible.top + (visible.height - h) / 2
        return Box(left, top, left + w, top + h)
    }

    /**
     * The visible area [crop] (in the camera buffer's own orientation, [width] by [height]) in
     * the upright orientation the barcode reader reports positions in, after turning the image
     * clockwise by [degrees].
     */
    fun upright(crop: Box, width: Int, height: Int, degrees: Int): Box = when (((degrees % 360) + 360) % 360) {
        90 -> Box(height - crop.bottom, crop.left, height - crop.top, crop.right)
        180 -> Box(width - crop.right, height - crop.bottom, width - crop.left, height - crop.top)
        270 -> Box(crop.top, width - crop.right, crop.bottom, width - crop.left)
        else -> crop
    }

    /** Whether [code] lies wholly inside the scan target of [visible]. */
    fun inTarget(code: Box, visible: Box): Boolean {
        val t = target(visible)
        return code.left >= t.left && code.top >= t.top && code.right <= t.right && code.bottom <= t.bottom
    }
}
