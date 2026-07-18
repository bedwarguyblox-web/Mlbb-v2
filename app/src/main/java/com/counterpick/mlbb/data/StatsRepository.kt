package com.counterpick.mlbb.data

import android.content.Context
import com.counterpick.mlbb.network.OpenMlbbClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class DataFreshness { LIVE, OFFLINE_FALLBACK }

/**
 * Serves hero lookups and matchup/synergy/win-rate numbers to the recommendation engine.
 *
 * This used to be a static seed baked into a local database that someone had to hand-edit
 * every patch. It no longer is: [refreshLive] pulls current win rates, counters, and
 * synergies straight from the OpenMLBB public API (see network/OpenMlbbClient.kt) and
 * that's what every score is computed from. There's nothing left to "update" by hand —
 * the only static piece left is the hero roster/icon mapping in [OfflineFallbackData],
 * which also doubles as a same-shaped fallback dataset for whenever live data can't be
 * reached (offline, API hiccup, draft happening on stadium wifi, etc.), so the app keeps
 * giving *an* answer instead of breaking mid-draft.
 *
 * Two things are deliberately throttled rather than fired on literally every recompute:
 *  - Bulk win rates refetch at most every [WINRATE_TTL_MS] per rank. Recommendations
 *    recompute on every tap (rank filter, hero pick, search) — hitting the network on
 *    every one of those would feel laggy and would hammer a free community API for no
 *    benefit, since win rates don't meaningfully change second to second.
 *  - Counters/synergy lookups are fetched per *locked-in* hero (at most 10 in a full
 *    draft) rather than per candidate (100+), and cached per hero once fetched. This is
 *    what keeps a live fetch to a handful of requests instead of hundreds.
 * Both caches refresh automatically in the background the next time they go stale —
 * nobody has to touch this file or hero_data.json to keep numbers current.
 */
class StatsRepository private constructor(
    private val offline: OfflineFallbackData
) {
    var heroesById: Map<Int, Hero> = offline.heroes.associateBy { it.id }
        private set
    var heroesByIconKey: Map<String, Hero> = heroesById.values.associateBy { it.iconKey }
        private set

    var freshness: DataFreshness = DataFreshness.OFFLINE_FALLBACK
        private set
    var lastLiveFetchAt: Long = 0L
        private set

    private val refreshMutex = Mutex()

    // remote (OpenMLBB) hero id <-> local hero id, built once from a name join.
    private var remoteToLocal: Map<Int, Int> = emptyMap()
    private var localToRemote: Map<Int, Int> = emptyMap()
    private var remoteIndexFetchedAt: Long = 0L

    // live per-rank win rates, keyed by local hero id
    private var liveWinRates: Map<Int, Double> = emptyMap()
    private var liveWinRatesRank: RankTier? = null
    private var liveWinRatesFetchedAt: Long = 0L

    // live per-hero counters/synergy, keyed by local hero id of the *locked* hero.
    // Value: list of (candidateLocalId, delta) — candidates that counter / pair with it.
    private data class CacheEntry(val rank: RankTier, val fetchedAt: Long, val rows: List<Pair<Int, Double>>)
    private val countersCache = mutableMapOf<Int, CacheEntry>()
    private val synergyCache = mutableMapOf<Int, CacheEntry>()

    /**
     * Ensures win-rate data for [rank] and counters/synergy data for every hero currently
     * locked in ([allyPicks] + [enemyPicks]) are as fresh as the TTLs allow, fetching
     * whatever's stale from the live API. Safe to call before every recompute — it's a
     * no-op (just cache reads) when everything's already fresh.
     */
    suspend fun refreshLive(rank: RankTier, allyPicks: List<Int>, enemyPicks: List<Int>) {
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            try {
                if (remoteToLocal.isEmpty() || now - remoteIndexFetchedAt > REMOTE_INDEX_TTL_MS) {
                    val index = OpenMlbbClient.fetchHeroIndex()
                    if (index.isNotEmpty()) {
                        val r2l = mutableMapOf<Int, Int>()
                        val l2r = mutableMapOf<Int, Int>()
                        for (hero in heroesById.values) {
                            val remoteId = index[normalize(hero.name)] ?: continue
                            r2l[remoteId] = hero.id
                            l2r[hero.id] = remoteId
                        }
                        remoteToLocal = r2l
                        localToRemote = l2r
                        remoteIndexFetchedAt = now
                    }
                }
                if (remoteToLocal.isEmpty()) return // couldn't resolve any hero remotely; stay on fallback

                if (liveWinRatesRank != rank || now - liveWinRatesFetchedAt > WINRATE_TTL_MS) {
                    val bulk = OpenMlbbClient.fetchBulkWinRates(rank)
                    if (bulk.isNotEmpty()) {
                        liveWinRates = bulk.mapNotNull { (remoteId, wr) ->
                            remoteToLocal[remoteId]?.let { it to wr }
                        }.toMap()
                        liveWinRatesRank = rank
                        liveWinRatesFetchedAt = now
                        freshness = DataFreshness.LIVE
                        lastLiveFetchAt = now
                    }
                }

                val lockedHeroIds = (allyPicks + enemyPicks).filter { it != -1 }.distinct()
                coroutineScope {
                    for (heroId in lockedHeroIds) {
                        val remoteId = localToRemote[heroId] ?: continue
                        val counterStale = countersCache[heroId]?.let {
                            it.rank != rank || now - it.fetchedAt > COUNTER_TTL_MS
                        } ?: true
                        if (counterStale) launch {
                            val rows = OpenMlbbClient.fetchCounters(remoteId, rank)
                                .mapNotNull { (rid, delta) -> remoteToLocal[rid]?.let { it to delta } }
                            if (rows.isNotEmpty()) {
                                countersCache[heroId] = CacheEntry(rank, now, rows)
                                freshness = DataFreshness.LIVE
                                lastLiveFetchAt = now
                            }
                        }
                        val synergyStale = synergyCache[heroId]?.let {
                            it.rank != rank || now - it.fetchedAt > COUNTER_TTL_MS
                        } ?: true
                        if (synergyStale) launch {
                            val rows = OpenMlbbClient.fetchCompatibility(remoteId, rank)
                                .mapNotNull { (rid, delta) -> remoteToLocal[rid]?.let { it to delta } }
                            if (rows.isNotEmpty()) {
                                synergyCache[heroId] = CacheEntry(rank, now, rows)
                                freshness = DataFreshness.LIVE
                                lastLiveFetchAt = now
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                // Any failure anywhere above just means we keep serving whatever we already
                // have cached (live or fallback) — a flaky network never crashes a draft.
            }
        }
    }

    /** Baseline win rate for [heroId] at [rank]. Live if we have it, else the offline snapshot. */
    fun baseWinRate(heroId: Int, rank: RankTier): Double {
        if (liveWinRatesRank == rank) liveWinRates[heroId]?.let { return it }
        return offline.rankWinRates["$heroId:${rank.name}"] ?: 0.50
    }

    /** How [heroId] fares specifically against [versusHeroId]. Live if cached, else offline/neutral. */
    fun matchupDelta(heroId: Int, versusHeroId: Int): Double {
        countersCache[versusHeroId]?.rows?.firstOrNull { it.first == heroId }?.let { return it.second }
        return offline.matchups["$heroId:$versusHeroId"] ?: 0.0
    }

    /** How [heroId] fares specifically alongside [withHeroId]. Live if cached, else offline/neutral. */
    fun synergyDelta(heroId: Int, withHeroId: Int): Double {
        synergyCache[withHeroId]?.rows?.firstOrNull { it.first == heroId }?.let { return it.second }
        return offline.synergies["$heroId:$withHeroId"] ?: 0.0
    }

    /**
     * Which heroes in [pool] would most punish picking [heroId]. This stays on the
     * offline/fallback table by design: it's a speculative "what might the enemy draft
     * next" check evaluated for every remaining candidate hero (100+), and firing a live
     * request per candidate would multiply request volume ~100x for a forward-looking
     * heuristic rather than a locked-in fact — not a good trade against a free API.
     */
    fun topCounterThreats(heroId: Int, pool: Collection<Int>, limit: Int = 3): List<Pair<Int, Double>> =
        pool.asSequence()
            .filter { it != heroId }
            .map { it to (offline.matchups["$it:$heroId"] ?: 0.0) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .toList()

    private fun normalize(name: String): String =
        name.lowercase().trim().replace(Regex("[^a-z0-9]"), "")

    companion object {
        private const val REMOTE_INDEX_TTL_MS = 12 * 60 * 60 * 1000L // 12h — roster barely changes
        private const val WINRATE_TTL_MS = 5 * 60 * 1000L            // 5m
        private const val COUNTER_TTL_MS = 10 * 60 * 1000L           // 10m

        @Volatile private var instance: StatsRepository? = null

        suspend fun get(context: Context): StatsRepository {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
            }
            val offline = OfflineFallbackData.load(context.applicationContext)
            val repo = StatsRepository(offline)
            synchronized(this) { instance = repo }
            return repo
        }
    }
}
