package com.counterpick.mlbb.data

import android.content.Context

/**
 * Loads the DB once into memory and serves fast lookups for the recommendation
 * engine and the vision matcher. Rebuild (call [refresh]) if hero_data.json changes
 * at runtime — normally only needed after a manual DB wipe + reseed.
 */
class StatsRepository private constructor(
    private val db: AppDatabase
) {
    var heroesById: Map<Int, Hero> = emptyMap()
        private set
    var heroesByIconKey: Map<String, Hero> = emptyMap()
        private set

    // key: "heroId:versusHeroId"
    private var matchupIndex: Map<String, Double> = emptyMap()
    // key: "heroId:withHeroId"
    private var synergyIndex: Map<String, Double> = emptyMap()
    // key: "heroId:RANK_NAME"
    private var winRateIndex: Map<String, Double> = emptyMap()

    suspend fun refresh() {
        heroesById = db.heroDao().getAll().associate { e ->
            e.id to Hero(
                id = e.id,
                name = e.name,
                iconKey = e.iconKey,
                roles = e.rolesCsv.split(",").filter { it.isNotBlank() }.map { HeroRole.valueOf(it) }
            )
        }
        heroesByIconKey = heroesById.values.associateBy { it.iconKey }

        matchupIndex = db.matchupDao().getAll().associate { "${it.heroId}:${it.versusHeroId}" to it.winRateDelta }
        synergyIndex = db.synergyDao().getAll().associate { "${it.heroId}:${it.withHeroId}" to it.winRateDelta }

        val winRates = mutableMapOf<String, Double>()
        for (rank in RankTier.entries) {
            db.rankWinRateDao().getForRank(rank.name).forEach {
                winRates["${it.heroId}:${rank.name}"] = it.winRate
            }
        }
        winRateIndex = winRates
    }

    /** Baseline win rate for [heroId] at [rank]; falls back to an even 0.50 if no data exists. */
    fun baseWinRate(heroId: Int, rank: RankTier): Double =
        winRateIndex["$heroId:${rank.name}"] ?: 0.50

    /** How [heroId] fares specifically against [versusHeroId]; 0.0 (neutral) if no data exists. */
    fun matchupDelta(heroId: Int, versusHeroId: Int): Double =
        matchupIndex["$heroId:$versusHeroId"] ?: 0.0

    /** How [heroId] fares specifically alongside [withHeroId]; 0.0 (neutral) if no data exists. */
    fun synergyDelta(heroId: Int, withHeroId: Int): Double =
        synergyIndex["$heroId:$withHeroId"] ?: 0.0

    /**
     * Inverse of [matchupDelta]: which heroes in [pool] would most punish picking [heroId],
     * i.e. heroes X where X-vs-heroId has the highest winRateDelta for X. Used to estimate
     * how exploitable a candidate is by picks the enemy hasn't locked in yet.
     */
    fun topCounterThreats(heroId: Int, pool: Collection<Int>, limit: Int = 3): List<Pair<Int, Double>> =
        pool.asSequence()
            .filter { it != heroId }
            .map { it to matchupDelta(it, heroId) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()

    companion object {
        @Volatile private var instance: StatsRepository? = null

        suspend fun get(context: Context): StatsRepository {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
            }
            val db = AppDatabase.get(context)
            SeedDataLoader.loadIfNeeded(context, db)
            val repo = StatsRepository(db)
            repo.refresh()
            synchronized(this) { instance = repo }
            return repo
        }
    }
}
