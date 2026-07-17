package com.counterpick.mlbb.vision

import android.content.Context
import com.google.gson.Gson
import java.io.File

/** A slot rectangle in normalized [0,1] screen-fraction coordinates. */
data class NormalizedRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

/**
 * Positions of the 10 pick slots (5 ally, 5 enemy) and ban strip on the draft screen,
 * as fractions of the captured frame's width/height. MLBB's draft UI position is stable
 * within a given client version + orientation but shifts across major UI overhauls and
 * aspect ratios, so this is calibratable rather than hardcoded logic: edit
 * draft_layout.json (copied into internal storage on first run) or overwrite it from
 * a future in-app calibration screen.
 *
 * Defaults below are set up for the common landscape draft screen: enemy picks along
 * the top edge, ally picks along the bottom edge, five evenly spaced slots each.
 */
data class DraftLayoutConfig(
    val allyPickSlots: List<NormalizedRect>,
    val enemyPickSlots: List<NormalizedRect>,
    val banSlots: List<NormalizedRect>
) {
    companion object {
        private const val FILE_NAME = "draft_layout.json"

        fun default(): DraftLayoutConfig {
            fun row(top: Double, bottom: Double): List<NormalizedRect> =
                (0 until 5).map { i ->
                    val left = 0.30 + i * 0.09
                    NormalizedRect(left, top, left + 0.08, bottom)
                }

            return DraftLayoutConfig(
                allyPickSlots = row(top = 0.88, bottom = 0.98),
                enemyPickSlots = row(top = 0.02, bottom = 0.12),
                banSlots = row(top = 0.13, bottom = 0.19)
            )
        }

        /** Loads a user-calibrated layout from internal storage, or writes and returns the default. */
        fun loadOrCreateDefault(context: Context): DraftLayoutConfig {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) {
                return runCatching {
                    Gson().fromJson(file.readText(), DraftLayoutConfig::class.java)
                }.getOrElse { default() }
            }
            val cfg = default()
            file.writeText(Gson().toJson(cfg))
            return cfg
        }

        fun save(context: Context, config: DraftLayoutConfig) {
            File(context.filesDir, FILE_NAME).writeText(Gson().toJson(config))
        }
    }
}
