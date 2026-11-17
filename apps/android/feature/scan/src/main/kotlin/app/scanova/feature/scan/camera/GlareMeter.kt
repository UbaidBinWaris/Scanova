package app.scanova.feature.scan.camera

import app.scanova.feature.scan.geometry.Quad

/**
 * Notices glare on the page in the live camera picture, so the person can
 * tilt the phone (or turn off the torch) *before* taking it: where a
 * reflection burns the paper white, the text under it is gone from the photo
 * and no filter can bring it back. [GlareSpotFinder] judges each frame.
 *
 * The answer has hysteresis, so the hint neither flickers nor chatters to a
 * screen reader: it comes on when a spot was seen in [ON_FRAMES] of the last
 * [WINDOW] frames, and goes off only once none of the last [WINDOW] frames
 * showed even a smaller spot ([RELEASE_SHARE]). At the analyser's 10 frames a
 * second that is 0.3 s to come on and 0.5 s to go.
 *
 * Used from the camera's analysis thread only.
 */
internal class GlareMeter {

    private val finder = GlareSpotFinder()
    private val strong = BooleanArray(WINDOW)
    private val weak = BooleanArray(WINDOW)
    private var next = 0

    /** The smoothed answer: the hint is showing. */
    var showing = false
        private set

    /**
     * This frame alone shows a glare spot as big as one that brings the hint up:
     * auto-capture does not take it, even before the hint shows. (Not the smaller
     * [RELEASE_SHARE]: auto-capture must never wait for a reason nobody is told.)
     */
    var inFrame = false
        private set

    /**
     * Looks at one frame. [luminance] is its brightness plane ([width] × [height],
     * one byte per pixel); [page] the page outline in fractions of that same
     * (unrotated) frame, or null when no page was found (then the middle of the
     * frame is looked at). Returns [showing].
     */
    fun onFrame(luminance: ByteArray, width: Int, height: Int, page: Quad?): Boolean =
        onSpot(finder.largestSpot(luminance, width, height, page))

    /** The smoothing on its own: one frame's largest spot, as a share of the page. */
    fun onSpot(share: Float): Boolean {
        strong[next] = GlareSpotFinder.isGlare(share)
        weak[next] = share >= RELEASE_SHARE
        inFrame = strong[next]
        next = (next + 1) % WINDOW
        showing = if (showing) weak.any { it } else strong.count { it } >= ON_FRAMES
        return showing
    }

    private companion object {
        const val WINDOW = 5
        const val ON_FRAMES = 3

        /** Once showing, a spot half the size still keeps the hint up. */
        const val RELEASE_SHARE = GlareSpotFinder.MIN_SPOT_SHARE / 2
    }
}
