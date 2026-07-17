package com.counterpick.mlbb.engine

import com.counterpick.mlbb.data.RankTier

/**
 * Snapshot of the draft as read off the pick/ban screen. Hero ids of -1 mean
 * "slot empty / not yet locked in" and are ignored by the recommendation engine.
 */
data class DraftState(
    val rank: RankTier,
    val allyPicks: List<Int>,   // up to 5 hero ids, -1 for empty slots
    val enemyPicks: List<Int>,  // up to 5 hero ids, -1 for empty slots
    val bannedHeroIds: Set<Int> // heroes unavailable to pick
) {
    fun lockedAllyPicks(): List<Int> = allyPicks.filter { it != -1 }
    fun lockedEnemyPicks(): List<Int> = enemyPicks.filter { it != -1 }

    companion object {
        fun empty(rank: RankTier) = DraftState(
            rank = rank,
            allyPicks = List(5) { -1 },
            enemyPicks = List(5) { -1 },
            bannedHeroIds = emptySet()
        )
    }
}
