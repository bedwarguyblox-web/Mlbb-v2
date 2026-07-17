package com.counterpick.mlbb.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "heroes")
data class HeroEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val iconKey: String,
    val rolesCsv: String // HeroRole names joined by ","
)

@Entity(tableName = "matchups", primaryKeys = ["heroId", "versusHeroId"])
data class MatchupEntity(
    val heroId: Int,
    val versusHeroId: Int,
    val winRateDelta: Double
)

@Entity(tableName = "synergies", primaryKeys = ["heroId", "withHeroId"])
data class SynergyEntity(
    val heroId: Int,
    val withHeroId: Int,
    val winRateDelta: Double
)

@Entity(tableName = "rank_winrates", primaryKeys = ["heroId", "rank"])
data class RankWinRateEntity(
    val heroId: Int,
    val rank: String, // RankTier.name
    val winRate: Double
)
