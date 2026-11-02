package com.havoice.assistant

/**
 * Decides when a barcode seen by the camera counts as a scan, for scanning one item after
 * another: a code counts once it's read the same way in [confirmFrames] frames in a row (one
 * frame can misread), and the same code can't count again until it has left the camera's view
 * for [goneFrames] frames in a row, so holding an item in front of the camera adds it once.
 */
class ScanGate(private val confirmFrames: Int = 2, private val goneFrames: Int = 8) {
    private var candidate: String? = null
    private var candidateCount = 0
    private var last: String? = null
    private var lastMissing = 0

    /**
     * Takes the codes read in one camera frame; returns a code that now counts as a new scan,
     * or null.
     */
    fun offer(codes: Collection<String>): String? {
        val lastStillHere = last != null && last in codes
        if (last != null) {
            lastMissing = if (lastStillHere) 0 else lastMissing + 1
            if (lastMissing >= goneFrames) last = null
        }
        val fresh = codes.firstOrNull { it != last }
        if (fresh == null) {
            candidate = null
            candidateCount = 0
            return null
        }
        candidateCount = if (fresh == candidate) candidateCount + 1 else 1
        candidate = fresh
        if (candidateCount < confirmFrames) return null
        last = fresh
        lastMissing = 0
        candidate = null
        candidateCount = 0
        return fresh
    }

    /** Lets the last code count again right away (after it was discarded or undone). */
    fun forget() {
        last = null
        candidate = null
        candidateCount = 0
    }
}
