package com.counterpick.mlbb.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStreamReader

private data class SeedHero(val id: Int, val name: String, val iconKey: String, val roles: List<String>)
private data class SeedMatchup(val heroId: Int, val versusHeroId: Int, val winRateDelta: Double)
private data class SeedSynergy(val heroId: Int, val withHeroId: Int, val winRateDelta: Double)
private data class SeedRankWinRate(val heroId: Int, val rank: String, val winRate: Double)

private data class SeedFile(
    val heroes: List<SeedHero>,
    val matchups: List<SeedMatchup>,
    val synergies: List<SeedSynergy>,
    @SerializedName("rankWinRates") val rankWinRates: List<SeedRankWinRate>
)

/**
 * Everything read from the bundled `assets/hero_data.json`. Two different jobs live in
 * here on purpose:
 *
 *  1. [heroes] is the permanent hero roster (id/name/iconKey/roles) — this is the one part
 *     of the file that's still hand-maintained, because iconKey maps to bundled art under
 *     assets/hero_icons/ that no API call can provide.
 *  2. [matchups]/[synergies]/[rankWinRates] are an *offline fallback snapshot* — only
 *     consulted by [StatsRepository] when a live fetch from OpenMLBB fails or hasn't
 *     completed yet. Under normal conditions with network access, none of these three
 *     maps are what actually drives a recommendation; see OpenMlbbClient.kt for the live
 *     path.
 */
class OfflineFallbackData private constructor(
    val heroes: List<Hero>,
    val matchups: Map<String, Double>,     // "heroId:versusHeroId" -> delta
    val synergies: Map<String, Double>,    // "heroId:withHeroId" -> delta
    val rankWinRates: Map<String, Double>  // "heroId:RANK_NAME" -> winRate
) {
    companion object {
        @Volatile private var cached: OfflineFallbackData? = null

        fun load(context: Context): OfflineFallbackData {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val json = context.assets.open("hero_data.json").use { stream ->
                    InputStreamReader(stream).readText()
                }
                val seed = Gson().fromJson(json, SeedFile::class.java)
                val result = OfflineFallbackData(
                    heroes = seed.heroes.map {
                        Hero(
                            id = it.id,
                            name = it.name,
                            iconKey = it.iconKey,
                            roles = it.roles.filter { r -> r.isNotBlank() }.map { r -> HeroRole.valueOf(r) }
                        )
                    },
                    matchups = seed.matchups.associate { "${it.heroId}:${it.versusHeroId}" to it.winRateDelta },
                    synergies = seed.synergies.associate { "${it.heroId}:${it.withHeroId}" to it.winRateDelta },
                    rankWinRates = seed.rankWinRates.associate { "${it.heroId}:${it.rank}" to it.winRate }
                )
                cached = result
                return result
            }
        }
    }
}
