package com.counterpick.mlbb.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.counterpick.mlbb.data.entities.HeroEntity
import com.counterpick.mlbb.data.entities.MatchupEntity
import com.counterpick.mlbb.data.entities.RankWinRateEntity
import com.counterpick.mlbb.data.entities.SynergyEntity

@Dao
interface HeroDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(heroes: List<HeroEntity>)

    @Query("SELECT * FROM heroes")
    suspend fun getAll(): List<HeroEntity>

    @Query("SELECT COUNT(*) FROM heroes")
    suspend fun count(): Int
}

@Dao
interface MatchupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(matchups: List<MatchupEntity>)

    @Query("SELECT * FROM matchups WHERE heroId = :heroId AND versusHeroId = :versusHeroId LIMIT 1")
    suspend fun get(heroId: Int, versusHeroId: Int): MatchupEntity?

    @Query("SELECT * FROM matchups")
    suspend fun getAll(): List<MatchupEntity>
}

@Dao
interface SynergyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(synergies: List<SynergyEntity>)

    @Query("SELECT * FROM synergies WHERE heroId = :heroId AND withHeroId = :withHeroId LIMIT 1")
    suspend fun get(heroId: Int, withHeroId: Int): SynergyEntity?

    @Query("SELECT * FROM synergies")
    suspend fun getAll(): List<SynergyEntity>
}

@Dao
interface RankWinRateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RankWinRateEntity>)

    @Query("SELECT * FROM rank_winrates WHERE rank = :rank")
    suspend fun getForRank(rank: String): List<RankWinRateEntity>
}
