package com.counterpick.mlbb.vision

import android.content.Context
import android.graphics.Bitmap
import com.counterpick.mlbb.data.Hero
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Matches a cropped screen region against known hero icon templates via normalized
 * cross-correlation (TM_CCOEFF_NORMED). Templates come from assets/hero_icons/<iconKey>.png
 * — square crops of each hero's portrait, extracted by the user from their own screenshots.
 * We can't ship Moonton's art assets, so this directory starts empty; matching degrades
 * gracefully (returns null / low confidence) for any hero without a template on disk.
 */
class HeroIconMatcher(context: Context, heroes: Collection<Hero>) {

    private data class Template(val hero: Hero, val mat: Mat)

    private val templates: List<Template> = heroes.mapNotNull { hero ->
        loadTemplate(context, hero)?.let { Template(hero, it) }
    }

    private fun loadTemplate(context: Context, hero: Hero): Mat? {
        val path = "hero_icons/${hero.iconKey}.png"
        return runCatching {
            context.assets.open(path).use { stream ->
                val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                val mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2RGB)
                Imgproc.resize(mat, mat, Size(64.0, 64.0))
                mat
            }
        }.getOrNull()
    }

    data class MatchResult(val hero: Hero, val confidence: Double)

    /** Best-matching hero for a cropped slot bitmap, or null if confidence is too low / no templates loaded. */
    fun match(slotBitmap: Bitmap, minConfidence: Double = 0.72): MatchResult? {
        if (templates.isEmpty()) return null

        val candidate = Mat()
        Utils.bitmapToMat(slotBitmap, candidate)
        Imgproc.cvtColor(candidate, candidate, Imgproc.COLOR_RGBA2RGB)
        Imgproc.resize(candidate, candidate, Size(64.0, 64.0))

        var best: MatchResult? = null
        for (template in templates) {
            val result = Mat()
            Imgproc.matchTemplate(candidate, template.mat, result, Imgproc.TM_CCOEFF_NORMED)
            val mmr = Core.minMaxLoc(result)
            result.release()

            if (best == null || mmr.maxVal > best.confidence) {
                best = MatchResult(template.hero, mmr.maxVal)
            }
        }
        candidate.release()

        return best?.takeIf { it.confidence >= minConfidence }
    }

    fun release() {
        templates.forEach { it.mat.release() }
    }
}
