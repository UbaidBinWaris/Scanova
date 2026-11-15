package app.scanova.feature.scan.geometry

/**
 * Decides when to take the picture automatically: the page outline has to be
 * found in every frame and stay put for a moment, like a person holding still
 * before pressing the shutter.
 *
 * Works on outlines in normalised coordinates (fractions of the frame), so the
 * tolerance does not depend on the camera resolution.
 */
class SteadinessTracker(
    /** How long the outline has to hold still. */
    private val requiredStableMillis: Long = 900,
    /** Largest corner movement, as a fraction of the frame, still counted as "still". */
    private val tolerance: Double = 0.025,
    /** Frames without an outline that are forgiven (detection flickers on real paper). */
    private val maxMissedFrames: Int = 2,
) {
    private var anchor: Quad? = null
    private var anchorTimeMillis: Long = 0
    private var missedFrames = 0
    private var fired = false

    /** Result of feeding one frame. */
    data class Reading(
        /** 0 when the page just appeared or moved, 1 when it has held still long enough. */
        val progress: Float,
        /** True exactly once per steady period: take the picture now. */
        val shouldCapture: Boolean,
    )

    fun onFrame(quad: Quad?, timeMillis: Long): Reading {
        if (quad == null) {
            missedFrames++
            if (missedFrames > maxMissedFrames) reset()
            return Reading(progress(timeMillis), shouldCapture = false)
        }
        missedFrames = 0
        val current = anchor
        if (current == null || current.maxCornerDistance(quad) > tolerance) {
            // New page, or it moved: start timing from this frame.
            anchor = quad
            anchorTimeMillis = timeMillis
            fired = false
            return Reading(0f, shouldCapture = false)
        }
        val progress = progress(timeMillis)
        if (progress >= 1f && !fired) {
            fired = true
            return Reading(1f, shouldCapture = true)
        }
        return Reading(progress, shouldCapture = false)
    }

    /** Forgets the current page, for example after a picture was taken. */
    fun reset() {
        anchor = null
        missedFrames = 0
        fired = false
    }

    private fun progress(timeMillis: Long): Float {
        if (anchor == null) return 0f
        val elapsed = (timeMillis - anchorTimeMillis).coerceAtLeast(0)
        return (elapsed.toFloat() / requiredStableMillis).coerceIn(0f, 1f)
    }
}

/** Smooths the outline drawn over the preview so it does not jitter from frame to frame. */
object QuadSmoother {
    /**
     * Moves [previous] towards [current] by [weight] (0..1). A jump larger than
     * [snapDistance] is taken at once: that is a different page, not jitter.
     */
    fun blend(previous: Quad?, current: Quad, weight: Double = 0.5, snapDistance: Double = 0.12): Quad {
        if (previous == null || previous.maxCornerDistance(current) > snapDistance) return current
        val a = previous.corners
        val b = current.corners
        val blended = a.indices.map { a[it] + (b[it] - a[it]) * weight }
        return Quad(blended[0], blended[1], blended[2], blended[3])
    }
}
