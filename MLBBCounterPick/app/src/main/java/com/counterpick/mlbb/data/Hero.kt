package com.counterpick.mlbb.data

/** MLBB rank tiers, low to high. Order matters — used for bracket lookups. */
enum class RankTier(val label: String) {
    WARRIOR_ELITE("Warrior/Elite"),
    MASTER_GRANDMASTER("Master/Grandmaster"),
    EPIC("Epic"),
    LEGEND("Legend"),
    MYTHIC("Mythic"),
    MYTHICAL_HONOR_GLORY("Mythical Honor/Glory")
}

enum class HeroRole {
    TANK, FIGHTER, ASSASSIN, MAGE, MARKSMAN, SUPPORT
}

/**
 * Immutable hero record. [iconKey] is the filename stem (no extension) expected
 * under assets/hero_icons/, used both for template matching and UI display.
 */
data class Hero(
    val id: Int,
    val name: String,
    val iconKey: String,
    val roles: List<HeroRole>
)

/** A directional matchup: how [heroId] performs specifically against [versusHeroId]. */
data class Matchup(
    val heroId: Int,
    val versusHeroId: Int,
    val winRateDelta: Double // e.g. +0.06 means a 6-point winrate swing in heroId's favor
)

/** A directional synergy: how [heroId] performs specifically alongside [withHeroId]. */
data class Synergy(
    val heroId: Int,
    val withHeroId: Int,
    val winRateDelta: Double
)

/** Baseline solo win rate for a hero within a rank bracket, independent of matchups. */
data class RankWinRate(
    val heroId: Int,
    val rank: RankTier,
    val winRate: Double // 0.0..1.0
)
