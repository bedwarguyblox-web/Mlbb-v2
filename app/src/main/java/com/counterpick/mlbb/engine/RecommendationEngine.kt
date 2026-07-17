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
    val counterAdvantage: Double,   // avg matchup delta vs current (locked) enemy picks
    val synergyAdvantage: Double,   // avg synergy delta with current ally picks
    val roleNeedBonus: Double,
    val exploitationRisk: Double,   // how hard this pick can be punished by enemy picks still open, >= 0
    val openEnemySlots: Int,        // enemy slots not yet locked in when this was scored
    val topThreats: List<Hero>,     // heroes (still available) that would most punish this pick
    val reasons: List<String>
)

/**
 * Weights are tuned toward "don't feed a lane counter" mattering more than raw synergy,
 * since a bad matchup is usually more decisive in MLBB than a good pairing. Adjust freely.
 *
 * Draft position (1st pick, 2nd, 5th, etc.) isn't tracked as an explicit field — it doesn't
 * need to be. Whatever position you're in is already reflected in how many ally/enemy slots
 * are locked when you call [recommend]. What the engine used to ignore was the *other* half of
 * that picture: enemy slots that are still open. [riskWeight] and [topCounterThreats] close that
 * gap by penalizing candidates that the remaining, not-yet-picked enemy pool could hard-counter —
 * so an early pick (lots of open enemy slots) gets nudged toward safer, less exploitable heroes,
 * while a last pick (zero open enemy slots) is scored purely on the locked-in matchups, same as
 * before.
 */
class RecommendationEngine(
    private val stats: StatsRepository,
    private val counterWeight: Double = 1.4,
    private val synergyWeight: Double = 0.8,
    private val roleNeedWeight: Double = 0.05,
    private val riskWeight: Double = 1.0,
    private val threatsConsidered: Int = 3
) {

    /** Returns available heroes ranked best pick first. */
    fun recommend(draft: DraftState, limit: Int = 5): List<HeroRecommendation> {
        val allyPicks = draft.lockedAllyPicks()
        val enemyPicks = draft.lockedEnemyPicks()
        val unavailable = draft.bannedHeroIds + draft.allyPicks.filter { it != -1 } + draft.enemyPicks.filter { it != -1 }
        val openEnemySlots = draft.enemyPicks.count { it == -1 }

        val allyRoles = allyPicks.mapNotNull { stats.heroesById[it] }.flatMap { it.roles }.toSet()
        val missingCoreRoles = setOf(HeroRole.TANK, HeroRole.MARKSMAN, HeroRole.MAGE) - allyRoles

        // Pool of heroes the enemy could still draft into their open slots — everything not
        // already banned or locked in by either side.
        val stillDraftablePool = stats.heroesById.keys - unavailable

        return stats.heroesById.values
            .filter { it.id !in unavailable }
            .map { candidate ->
                score(candidate, draft, allyPicks, enemyPicks, missingCoreRoles, openEnemySlots, stillDraftablePool)
            }
            .sortedByDescending { it.estimatedWinChance }
            .take(limit)
    }

    private fun score(
        candidate: Hero,
        draft: DraftState,
        allyPicks: List<Int>,
        enemyPicks: List<Int>,
        missingCoreRoles: Set<HeroRole>,
        openEnemySlots: Int,
        stillDraftablePool: Set<Int>
    ): HeroRecommendation {
        val base = stats.baseWinRate(candidate.id, draft.rank)

        val counterDeltas = enemyPicks.map { stats.matchupDelta(candidate.id, it) }
        val counterAvg = if (counterDeltas.isEmpty()) 0.0 else counterDeltas.sum() / counterDeltas.size

        val synergyDeltas = allyPicks.map { stats.synergyDelta(candidate.id, it) }
        val synergyAvg = if (synergyDeltas.isEmpty()) 0.0 else synergyDeltas.sum() / synergyDeltas.size

        val fillsMissingRole = candidate.roles.any { it in missingCoreRoles }
        val roleBonus = if (fillsMissingRole) roleNeedWeight else 0.0

        // Forward-looking risk: if the enemy still has open slots, how badly could the
        // best-remaining counters in the draftable pool punish this pick? No open slots (last
        // pick, or enemy team already full) means zero speculative risk — the matchup is fully
        // known and already captured by counterAvg.
        val threats = if (openEnemySlots > 0) {
            stats.topCounterThreats(candidate.id, stillDraftablePool - candidate.id, threatsConsidered)
        } else {
            emptyList()
        }
        val threatAvg = if (threats.isEmpty()) 0.0 else threats.sumOf { it.second } / threats.size
        // Scale by how many shots the enemy gets to land one of those threats — 1 open slot is
        // already enough exposure, so this saturates fast rather than scaling linearly to 5.
        val exposure = min(1.0, openEnemySlots / 2.0)
        val exploitationRisk = threatAvg * exposure

        val rawScore = base + counterWeight * counterAvg + synergyWeight * synergyAvg + roleBonus - riskWeight * exploitationRisk
        val clamped = max(0.05, min(0.95, rawScore))

        val threatHeroes = threats.mapNotNull { (id, _) -> stats.heroesById[id] }

        val reasons = buildList {
            if (counterAvg > 0.015) add("Favorable matchup vs current enemy picks")
            if (counterAvg < -0.015) add("Slightly unfavorable vs enemy picks — pick with care")
            if (synergyAvg > 0.015) add("Strong synergy with your locked-in allies")
            if (fillsMissingRole) add("Fills a role gap in your comp (${missingCoreRoles.joinToString { it.name.lowercase() }})")
            if (openEnemySlots > 0 && exploitationRisk > 0.02) {
                val names = threatHeroes.joinToString { it.name }
                add("Risky with $openEnemySlots enemy pick(s) still open — exploitable by $names")
            } else if (openEnemySlots > 0 && threatHeroes.isEmpty()) {
                add("Low exposure — no strong counters left in the available pool")
            }
            if (isEmpty()) add("Solid baseline pick for ${draft.rank.label}")
        }

        return HeroRecommendation(
            hero = candidate,
            estimatedWinChance = clamped,
            baseWinRate = base,
            counterAdvantage = counterAvg,
            synergyAdvantage = synergyAvg,
            roleNeedBonus = roleBonus,
            exploitationRisk = exploitationRisk,
            openEnemySlots = openEnemySlots,
            topThreats = threatHeroes,
            reasons = reasons
        )
    }
}
