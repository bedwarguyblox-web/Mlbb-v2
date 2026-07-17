package com.counterpick.mlbb.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.counterpick.mlbb.data.entities.HeroEntity
import com.counterpick.mlbb.data.entities.MatchupEntity
import com.counterpick.mlbb.data.entities.RankWinRateEntity
import com.counterpick.mlbb.data.entities.SynergyEntity

@Database(
    entities = [HeroEntity::class, MatchupEntity::class, SynergyEntity::class, RankWinRateEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun heroDao(): HeroDao
    abstract fun matchupDao(): MatchupDao
    abstract fun synergyDao(): SynergyDao
    abstract fun rankWinRateDao(): RankWinRateDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "counterpick.db"
                ).build().also { instance = it }
            }
    }
}
