package com.counterpick.mlbb.vision

import android.graphics.Bitmap
import com.counterpick.mlbb.data.RankTier
import com.counterpick.mlbb.engine.DraftState

class DraftScanner(
    private val layout: DraftLayoutConfig,
    private val matcher: HeroIconMatcher
) {

    /** Reads a full captured frame and produces the current draft state. */
    fun scan(frame: Bitmap, rank: RankTier): DraftState {
        val allyIds = layout.allyPickSlots.map { rect -> resolveSlot(frame, rect) }
        val enemyIds = layout.enemyPickSlots.map { rect -> resolveSlot(frame, rect) }
        val bannedIds = layout.banSlots.mapNotNull { rect ->
            resolveSlot(frame, rect).takeIf { it != -1 }
        }.toSet()

        return DraftState(
            rank = rank,
            allyPicks = allyIds,
            enemyPicks = enemyIds,
            bannedHeroIds = bannedIds
        )
    }

    private fun resolveSlot(frame: Bitmap, rect: NormalizedRect): Int {
        val crop = cropNormalized(frame, rect) ?: return -1
        val result = matcher.match(crop)
        crop.recycle()
        return result?.hero?.id ?: -1
    }

    private fun cropNormalized(frame: Bitmap, rect: NormalizedRect): Bitmap? {
        val left = (rect.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = (rect.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = (rect.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = (rect.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(frame, left, top, width, height)
    }
}
