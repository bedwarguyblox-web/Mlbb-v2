package org.opencv.core

/**
 * PLACEHOLDER — not the real OpenCV. See /opencv/README.md.
 * Mirrors just enough of org.opencv.core.Mat's shape for HeroIconMatcher to compile and run.
 */
class Mat {
    fun release() { /* no-op */ }
}

class Size(val width: Double, val height: Double)

object CvType {
    const val CV_8UC4 = 24
}

object Core {
    /** Mirrors org.opencv.core.Core.MinMaxLocResult's shape (only maxVal is used by this app). */
    class MinMaxLocResult {
        @JvmField var minVal: Double = 0.0
        @JvmField var maxVal: Double = 0.0
    }

    /** Always reports "no match" (maxVal = 0.0) — see /opencv/README.md to enable real matching. */
    fun minMaxLoc(m: Mat): MinMaxLocResult = MinMaxLocResult()
}
