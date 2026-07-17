package org.opencv.imgproc

import org.opencv.core.Mat
import org.opencv.core.Size

/** PLACEHOLDER — not the real OpenCV. See /opencv/README.md. */
object Imgproc {
    const val COLOR_RGBA2RGB = 0
    const val TM_CCOEFF_NORMED = 5

    fun cvtColor(src: Mat, dst: Mat, code: Int) { /* no-op */ }
    fun resize(src: Mat, dst: Mat, size: Size) { /* no-op */ }

    /** Always leaves [result] such that Core.minMaxLoc reports no match. */
    fun matchTemplate(image: Mat, templ: Mat, result: Mat, method: Int) { /* no-op */ }
}
