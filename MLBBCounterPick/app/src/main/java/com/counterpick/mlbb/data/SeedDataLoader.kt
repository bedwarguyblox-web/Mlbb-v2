package com.counterpick.mlbb.data

import android.content.Context
import com.counterpick.mlbb.data.entities.HeroEntity
import com.counterpick.mlbb.data.entities.MatchupEntity
import com.counterpick.mlbb.data.entities.RankWinRateEntity
import com.counterpick.mlbb.data.entities.SynergyEntity
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
 * Loads assets/hero_data.json into the Room database on first app launch.
 * Safe to call every launch — it's a cheap idempotent REPLACE upsert, so editing
 * hero_data.json and reinstalling the app is the intended way to update stats each patch.
 */
object SeedDataLoader {

    suspend fun loadIfNeeded(context: Context, db: AppDatabase) {
        if (db.heroDao().count() > 0) return
        load(context, db)
    }

    suspend fun load(context: Context, db: AppDatabase) {
        val json = context.assets.open("hero_data.json").use { stream ->
            InputStreamReader(stream).readText()
        }
        val seed = Gson().fromJson(json, SeedFile::class.java)

        db.heroDao().insertAll(
            seed.heroes.map {
                HeroEntity(id = it.id, name = it.name, iconKey = it.iconKey, rolesCsv = it.roles.joinToString(","))
            }
        )
        db.matchupDao().insertAll(
            seed.matchups.map { MatchupEntity(it.heroId, it.versusHeroId, it.winRateDelta) }
        )
        db.synergyDao().insertAll(
            seed.synergies.map { SynergyEntity(it.heroId, it.withHeroId, it.winRateDelta) }
        )
        db.rankWinRateDao().insertAll(
            seed.rankWinRates.map { RankWinRateEntity(it.heroId, it.rank, it.winRate) }
        )
    }
}
