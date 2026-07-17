package com.counterpick.mlbb.engine

import com.counterpick.mlbb.data.Hero
import com.counterpick.mlbb.data.HeroRole
import com.counterpick.mlbb.data.StatsRepository
import kotlin.math.max
import kotlin.math.min

data class HeroRecommendation(
    val hero: Hero,
    val estimatedWinChance: Double, // 0.0..1.0, clamped
    val baseWinRate: Double,
    val counterAdvantage: Double,   // avg matchup delta vs current enemy picks
    val synergyAdvantage: Double,   // avg synergy delta with current ally picks
    val roleNeedBonus: Double,
    val reasons: List<String>
)

/**
 * Weights are tuned toward "don't feed a lane counter" mattering more than raw synergy,
 * since a bad matchup is usually more decisive in MLBB than a good pairing. Adjust freely.
 */
class RecommendationEngine(
    private val stats: StatsRepository,
    private val counterWeight: Double = 1.4,
    private val synergyWeight: Double = 0.8,
    private val roleNeedWeight: Double = 0.05
) {

    /** Returns available heroes ranked best pick first. */
    fun recommend(draft: DraftState, limit: Int = 5): List<HeroRecommendation> {
        val allyPicks = draft.lockedAllyPicks()
        val enemyPicks = draft.lockedEnemyPicks()
        val unavailable = draft.bannedHeroIds + draft.allyPicks.filter { it != -1 } + draft.enemyPicks.filter { it != -1 }

        val allyRoles = allyPicks.mapNotNull { stats.heroesById[it] }.flatMap { it.roles }.toSet()
        val missingCoreRoles = setOf(HeroRole.TANK, HeroRole.MARKSMAN, HeroRole.MAGE) - allyRoles

        return stats.heroesById.values
            .filter { it.id !in unavailable }
            .map { candidate -> score(candidate, draft, allyPicks, enemyPicks, missingCoreRoles) }
            .sortedByDescending { it.estimatedWinChance }
            .take(limit)
    }

    private fun score(
        candidate: Hero,
        draft: DraftState,
        allyPicks: List<Int>,
        enemyPicks: List<Int>,
        missingCoreRoles: Set<HeroRole>
    ): HeroRecommendation {
        val base = stats.baseWinRate(candidate.id, draft.rank)

        val counterDeltas = enemyPicks.map { stats.matchupDelta(candidate.id, it) }
        val counterAvg = if (counterDeltas.isEmpty()) 0.0 else counterDeltas.sum() / counterDeltas.size

        val synergyDeltas = allyPicks.map { stats.synergyDelta(candidate.id, it) }
        val synergyAvg = if (synergyDeltas.isEmpty()) 0.0 else synergyDeltas.sum() / synergyDeltas.size

        val fillsMissingRole = candidate.roles.any { it in missingCoreRoles }
        val roleBonus = if (fillsMissingRole) roleNeedWeight else 0.0

        val rawScore = base + counterWeight * counterAvg + synergyWeight * synergyAvg + roleBonus
        val clamped = max(0.05, min(0.95, rawScore))

        val reasons = buildList {
            if (counterAvg > 0.015) add("Favorable matchup vs current enemy picks")
            if (counterAvg < -0.015) add("Slightly unfavorable vs enemy picks — pick with care")
            if (synergyAvg > 0.015) add("Strong synergy with your locked-in allies")
            if (fillsMissingRole) add("Fills a role gap in your comp (${missingCoreRoles.joinToString { it.name.lowercase() }})")
            if (isEmpty()) add("Solid baseline pick for ${draft.rank.label}")
        }

        return HeroRecommendation(
            hero = candidate,
            estimatedWinChance = clamped,
            baseWinRate = base,
            counterAdvantage = counterAvg,
            synergyAdvantage = synergyAvg,
            roleNeedBonus = roleBonus,
            reasons = reasons
        )
    }
}
