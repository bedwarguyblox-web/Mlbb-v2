import json, hashlib

# NOTE (2026-07-18): hero_data_orig.json used below predates several real heroes and
# is also just missing a few established ones outright — confirmed missing: Marcel
# (#132, Support/Tank, released Mar 2026), Sora (Fighter/Assassin, released ~Jan 2026),
# Hirara (#133, Assassin, released Jun 2026), Alucard (Fighter, one of the original
# launch heroes), Badang (Fighter), Hanzo (Assassin). All six were patched directly
# into the *compiled* app/src/main/assets/hero_data.json's `heroes` array. Re-running
# this script regenerates hero_data.json from hero_data_orig.json from scratch and
# will silently drop all six again unless hero_data_orig.json is updated first to
# include them.
#
# NOTE (2026-07-19): app/src/main/assets/hero_data.json's `rankWinRates` was rebuilt
# from real data, not this script's estimates below. Source: https://github.com/
# Pren7/MLBB-Winrate (raw file: raw.githubusercontent.com/Pren7/MLBB-Winrate/refs/
# heads/main/winrate.json) — scraped directly from Moonton's own official site
# (m.mobilelegends.com/rank), auto-refreshed daily via that repo's own GitHub Actions
# cron job. Covers 132 of 133 heroes (only Hirara has no live number yet — too new for
# ranked stats to exist). One important limitation: that source only publishes a
# single "All Rank, past 1 day" win rate per hero, not a per-rank-tier breakdown — so
# the compiled JSON currently applies that same number identically across all 6
# RankTier entries per hero, rather than genuinely different numbers per rank. This
# script's tier-list-based estimation logic below is now stale for `rankWinRates`
# specifically (kept for reference / as a fallback method) — if you re-run this
# script, re-pull the live file above afterward and re-apply it the same way, or the
# compiled JSON will regress back to guesses. `matchups` and `synergies` (counters/
# synergy deltas) are NOT covered by that source and still come from this script's
# original hand-curated tier-list estimates below — that part hasn't changed.

d = json.load(open('hero_data_orig.json'))
heroes = d['heroes']  # keep as-is, id/name/iconKey/roles untouched
by_name = {h['name']: h for h in heroes}
name_to_id = {h['name']: h['id'] for h in heroes}
ids = set(h['id'] for h in heroes)

RANKS = ["WARRIOR_ELITE", "MASTER_GRANDMASTER", "EPIC", "LEGEND", "MYTHIC", "MYTHICAL_HONOR_GLORY"]

# ---------------------------------------------------------------------------
# 1) Baseline win-rate tiering, grounded in GameMarket.gg's Patch 2.1.90
#    (July 2026) meta tier list -- https://gamemarket.gg/news/mobile-legends-bang-bang/
#    best-mlbb-heroes-july-2026-patch-2-1-90-meta-tier-list -- plus long-running,

#    multi-source-corroborated MLBB community consensus (esports.gg, mlbbhub.com,
#    frvr.com tier lists cross-checked June-July 2026) for heroes that article
#    didn't call out directly. Heroes with no specific current-patch signal keep a
#    neutral baseline (system falls back to 0.50 same as before) with only a tiny
#    deterministic spread so the list doesn't visually tie -- NOT a claim of real
#    per-hero data for those. Replace any of this with your own scraped numbers
#    whenever you have them; that always wins over this seed.
# ---------------------------------------------------------------------------

S_TIER = ["Kimmy", "Karrie", "Melissa", "Hanabi", "Zhuxin", "Gord", "Yve", "Gloo",
          "Minotaur", "Rafaela", "Floryn", "Belerick", "Atlas", "Esmeralda", "Masha",
          "Paquito", "Argus", "Hayabusa", "Yi Sun-shin", "Julian", "Nolan", "Aulus"]

A_TIER = ["Beatrix", "Brody", "Ixia", "Lylia", "Novaria", "Valentina", "Tigreal",
          "Angela", "Mathilda", "Chip", "Minsitthar", "Yu Zhong", "Fredrinn",
          "Lancelot", "Ling", "Fanny", "Joy"]

# Long-standing, multi-patch "reliable pick" reputations not called out in the
# July 2026 article specifically -- kept as a mild bump, not S/A.
PLUS_TIER = ["Chou", "Franco", "Khufra", "Lolita", "Estes", "Diggie", "Selena",
             "Harith", "Valir", "Vexana", "Cecilion", "Kagura", "Pharsa", "Wanwan",
             "Claude", "Moskov", "Granger", "Natan", "Popol and Kupa", "Terizla",
             "Silvanna", "Yin", "Guinevere", "Benedetta", "Helcurt", "Saber",
             "Natalia", "Kadita", "Faramis", "Carmilla", "Kaja", "Gusion", "Freya",
             "Arlott", "Lapu-Lapu", "Phoveus", "Khaleed", "X.Borg"]

# Heroes explicitly nerfed this patch per the article, or long considered
# below-average / outclassed picks.
NERFED = ["Baxia", "Akai"]
MINUS_TIER = ["Layla", "Miya", "Balmond", "Alpha", "Leomord", "Hylos", "Uranus",
              "Jawhead", "Grock", "Barats", "Gatotkaca", "Eudora", "Cyclops",
              "Zhask", "Vale"]

def base_offset(name):
    if name in S_TIER: return 0.045
    if name in A_TIER: return 0.028
    if name in PLUS_TIER: return 0.015
    if name in NERFED: return -0.025
    if name in MINUS_TIER: return -0.018
    return 0.0

# Small deterministic per-hero jitter (+/-0.006) so untagged heroes aren't all
# identically 50.0% -- derived from a hash of the name, not randomness, so it's
# reproducible.
def jitter(name):
    h = int(hashlib.sha1(name.encode()).hexdigest(), 16)
    return ((h % 13) - 6) / 1000.0  # -0.006..+0.006

# Mechanically demanding heroes: reward high-rank play, punish low-rank play.
HIGH_SKILL = ["Fanny", "Ling", "Gusion", "Hayabusa", "Lancelot", "Helcurt", "Harley",
              "Aamon", "Selena", "Benedetta", "Karina", "Kadita", "Julian", "Yin",
              "Suyou", "Joy", "Chou", "Nolan"]
# Beginner-friendly / low-skill-floor heroes: overperform at low rank, flatten out
# (or dip slightly) at the top where opponents punish predictability.
LOW_SKILL = ["Layla", "Miya", "Tigreal", "Franco", "Eudora", "Zilong", "Balmond",
             "Moskov", "Nana", "Alice", "Odette", "Cyclops", "Vexana", "Bane"]

RANK_GRADIENT = [-0.020, -0.012, -0.004, 0.004, 0.012, 0.020]  # low -> high rank

rank_winrates = []
for h in heroes:
    name = h['name']
    base = 0.50 + base_offset(name) + jitter(name)
    for i, rank in enumerate(RANKS):
        wr = base
        if name in HIGH_SKILL:
            wr += RANK_GRADIENT[i]
        elif name in LOW_SKILL:
            wr -= RANK_GRADIENT[i]
        wr = max(0.42, min(0.60, round(wr, 4)))
        rank_winrates.append({"heroId": h['id'], "rank": rank, "winRate": wr})

# ---------------------------------------------------------------------------
# 2) Matchups -- curated hard-counter relationships. These reflect long-running,
#    widely corroborated MLBB counter-pick knowledge (kit interactions: hard CC
#    vs mobility ults, true damage vs stacked defense, sustain vs burst, etc.)
#    repeated across many independent guide sites for multiple patches, plus the
#    Patch 2.1.90 anti-heal/anti-dive notes from the July 2026 GameMarket.gg
#    article. Each row is directional: heroId's winRateDelta vs versusHeroId.
#    This is NOT exhaustive (127 heroes = ~16,000 possible ordered pairs) -- it
#    covers the matchups that show up most often in ranked drafts. Missing pairs
#    fall back to neutral (0.0), same as before.
# ---------------------------------------------------------------------------

def gid(name):
    return name_to_id[name]

matchup_groups = [
    # (counter_hero, [victims...], delta)
    ("Khufra", ["Ling", "Fanny", "Gusion", "Harley", "Aamon", "Suyou"], 0.07),
    ("Chou", ["Zilong", "Aldous", "Fanny", "Ling", "Helcurt", "Lancelot"], 0.06),
    ("Franco", ["Layla", "Miya", "Cyclops", "Eudora", "Vexana", "Kimmy"], 0.06),
    ("Baxia", ["Kagura", "Chang'e", "Lunox", "Valentina", "Pharsa"], 0.05),
    ("Diggie", ["Atlas", "Chip", "Tigreal", "Karrie", "Franco", "Vale"], 0.05),
    ("Belerick", ["Miya", "Layla", "Karrie", "Claude", "Moskov", "Wanwan"], 0.06),
    ("Atlas", ["Cyclops", "Eudora", "Vexana", "Layla", "Zhask"], 0.05),
    ("Hylos", ["Miya", "Layla", "Claude", "Moskov"], 0.04),
    ("Uranus", ["Karrie", "Bruno", "Moskov", "Claude"], 0.04),
    ("Gloo", ["Lancelot", "Gusion", "Helcurt", "Saber", "Aamon"], 0.05),
    ("Minotaur", ["Fanny", "Ling", "Harley", "Aamon"], 0.04),
    ("Terizla", ["Karrie", "Claude", "Moskov", "Wanwan", "Brody"], 0.05),
    ("X.Borg", ["Angela", "Faramis", "Estes", "Nana"], 0.04),
    ("Silvanna", ["Zilong", "Balmond", "Alpha", "Sun"], 0.04),
    ("Yu Zhong", ["Lylia", "Cecilion", "Valentina", "Zhask"], 0.05),
    ("Esmeralda", ["Karrie", "Bruno", "Claude", "Moskov"], 0.05),
    ("Minsitthar", ["Fanny", "Ling", "Julian", "Benedetta", "Yin"], 0.06),
    ("Thamuz", ["Tigreal", "Uranus", "Hylos", "Baxia"], 0.04),
    ("Argus", ["Gord", "Pharsa", "Eudora", "Zhask"], 0.04),
    ("Lancelot", ["Kagura", "Pharsa", "Cecilion", "Zhask", "Eudora"], 0.06),
    ("Gusion", ["Kimmy", "Layla", "Cecilion", "Lylia"], 0.06),
    ("Helcurt", ["Kagura", "Wanwan", "Granger", "Karina", "Lylia"], 0.06),
    ("Natalia", ["Gord", "Cecilion", "Zhask", "Vale", "Eudora"], 0.05),
    ("Saber", ["Gusion", "Karina", "Harley", "Lancelot"], 0.05),
    ("Ling", ["Cecilion", "Zhask", "Vale", "Pharsa"], 0.05),
    ("Fanny", ["Cecilion", "Zhask", "Aurora", "Vale"], 0.06),
    ("Julian", ["Layla", "Miya", "Cecilion", "Kimmy"], 0.06),
    ("Pharsa", ["Tigreal", "Franco", "Atlas", "Khufra"], 0.03),
    ("Eudora", ["Lancelot", "Gusion", "Helcurt", "Natalia"], 0.04),
    ("Kagura", ["Chou", "Zilong", "Yu Zhong", "Aldous"], 0.03),
    ("Lylia", ["Balmond", "Zilong", "Sun", "Alpha"], 0.04),
    ("Cecilion", ["Uranus", "Hylos", "Baxia", "Grock"], 0.04),
    ("Valir", ["Fanny", "Ling", "Chou", "Lancelot"], 0.04),
    ("Karrie", ["Tigreal", "Franco", "Uranus", "Hylos", "Baxia", "Grock"], 0.06),
    ("Wanwan", ["Aldous", "Balmond", "Terizla", "Yu Zhong"], 0.05),
    ("Brody", ["Layla", "Miya", "Nana", "Cyclops"], 0.05),
    ("Claude", ["Grock", "Barats", "Uranus", "Hylos"], 0.04),
    ("Moskov", ["Balmond", "Alpha", "Zilong"], 0.03),
    ("Melissa", ["Lancelot", "Gusion", "Helcurt", "Saber", "Ling"], 0.05),
    ("Hanabi", ["Kaja", "Chou", "Franco", "Tigreal"], 0.04),
    ("Angela", ["Natalia", "Helcurt", "Saber"], 0.03),
    ("Mathilda", ["Hayabusa", "Nolan", "Aulus", "Lancelot"], 0.04),
    ("Faramis", ["Aldous", "Grock", "Barats"], 0.03),
    ("Rafaela", ["Franco", "Khufra", "Kaja", "Diggie"], 0.03),
    ("Zhuxin", ["Tigreal", "Franco", "Atlas", "Grock"], 0.04),
    ("Gord", ["Layla", "Miya", "Cyclops", "Odette"], 0.03),
    ("Yve", ["Zilong", "Balmond", "Sun", "Alpha"], 0.03),
    ("Hayabusa", ["Kagura", "Pharsa", "Cecilion", "Zhask"], 0.05),
    ("Yi Sun-shin", ["Grock", "Barats", "Uranus", "Hylos"], 0.04),
    ("Nolan", ["Cecilion", "Zhask", "Lylia", "Vale"], 0.05),
    ("Aulus", ["Grock", "Barats", "Uranus"], 0.04),
    ("Masha", ["Cecilion", "Zhask", "Vale", "Aurora"], 0.05),
    ("Paquito", ["Layla", "Miya", "Cyclops", "Nana"], 0.05),
    ("Esmeralda", ["Lesley", "Wanwan", "Karrie"], -0.03),  # anti-heal marksmen punish Esmeralda's shield
    ("Karrie", ["Belerick", "Uranus"], -0.02),  # thorn/regen tanks blunt her true-damage plan
    ("Selena", ["Fanny", "Ling", "Lancelot", "Gusion"], 0.04),
    ("Kadita", ["Franco", "Tigreal", "Khufra"], 0.04),
    ("Vexana", ["Balmond", "Zilong", "Alpha"], 0.03),
    ("Guinevere", ["Layla", "Miya", "Cyclops"], 0.04),
    ("Benedetta", ["Cecilion", "Zhask", "Vale"], 0.05),
    ("Kaja", ["Layla", "Miya", "Kimmy", "Wanwan"], 0.05),
]

matchups = []
for winner, victims, delta in matchup_groups:
    if winner not in name_to_id:
        continue
    wid = gid(winner)
    for v in victims:
        if v not in name_to_id:
            continue
        matchups.append({"heroId": wid, "versusHeroId": gid(v), "winRateDelta": delta})

# ---------------------------------------------------------------------------
# 3) Synergies -- well-documented duo pairings (engage->follow-up burst, peel
#    supports with hyper-carries, roam-jungle dive pairs, sustain pairs with the
#    July 2026 patch's healing-core meta). Directional: heroId's winRateDelta
#    when paired with withHeroId.
# ---------------------------------------------------------------------------

synergy_groups = [
    ("Tigreal", ["Gusion", "Lunox", "Chang'e", "Zhuxin", "Karina"], 0.05),
    ("Atlas", ["Lunox", "Chang'e", "Pharsa", "Valentina", "Zhuxin"], 0.05),
    ("Khufra", ["Fanny", "Ling", "Gusion", "Selena"], 0.04),
    ("Franco", ["Selena", "Gusion", "Karina", "Aurora"], 0.04),
    ("Minotaur", ["Chang'e", "Pharsa", "Xavier", "Valentina"], 0.05),
    ("Estes", ["Karrie", "Claude", "Wanwan", "Granger", "Bruno"], 0.03),
    ("Rafaela", ["Claude", "Karrie", "Wanwan", "Bruno"], 0.04),
    ("Angela", ["Claude", "Granger", "Karrie", "Hayabusa", "Aulus"], 0.05),
    ("Diggie", ["Karrie", "Claude", "Hanabi", "Wanwan"], 0.03),
    ("Mathilda", ["Ling", "Lancelot", "Hayabusa", "Nolan", "Aulus"], 0.05),
    ("Floryn", ["Esmeralda", "Argus", "Paquito", "Masha"], 0.04),
    ("Faramis", ["Barats", "Aldous", "Grock", "Balmond"], 0.03),
    ("Kaja", ["Valentina", "Lunox", "Cecilion", "Xavier"], 0.05),
    ("Silvanna", ["Kagura", "Pharsa", "Gord"], 0.03),
    ("Lolita", ["Layla", "Miya", "Bruno"], 0.03),
    ("Diggie", ["Odette", "Vale", "Aurora"], 0.03),
    ("Selena", ["Chou", "Karina", "Helcurt"], 0.04),
    ("Nana", ["Karrie", "Claude", "Wanwan"], 0.03),
    ("Carmilla", ["Karrie", "Claude", "Wanwan", "Bruno"], 0.03),
    ("Chip", ["Karrie", "Claude", "Melissa"], 0.03),
    ("Belerick", ["Zhuxin", "Gord", "Yve"], 0.03),
    ("Johnson", ["Gusion", "Karina", "Lancelot", "Aamon"], 0.04),
    ("Yu Zhong", ["Rafaela", "Angela", "Diggie"], 0.03),
]

synergies = []
for a, partners, delta in synergy_groups:
    if a not in name_to_id:
        continue
    aid = gid(a)
    for p in partners:
        if p not in name_to_id:
            continue
        synergies.append({"heroId": aid, "withHeroId": gid(p), "winRateDelta": delta})

out = {
    "_comment": (
        "Seed dataset generated from the official MLBB hero roster (names/roles cross-checked "
        "against mobilelegends.com and the MLBB Wiki). Hero IDs are grouped by primary role in "
        "blocks of 100 (Tank 100s, Fighter 200s, Assassin 300s, Mage 400s, Marksman 500s, Support "
        "600s). rankWinRates baselines are grounded in the GameMarket.gg Patch 2.1.90 (July 2026) "
        "meta tier list (S/A tier calls) plus multi-source community consensus for older, "
        "well-established picks, with a per-rank skill-curve adjustment for mechanically demanding "
        "vs. beginner-friendly heroes; heroes without a specific current-patch signal keep a "
        "near-neutral baseline. matchups/synergies encode well-corroborated kit-interaction "
        "counters and duo synergies repeated across independent MLBB guides over multiple patches. "
        "None of this is a live scrape of Moonton's internal stats (no public API exists) -- treat "
        "it as a curated, sourced starting point and replace rows with your own tracked match data "
        "per patch when you have it. Two heroes referenced in the July 2026 tier-list source "
        "(Sora, Marcel) are not yet in this roster and should be added on the next hero-list refresh. "
        "See /DATA_SOURCE_PROMPT.md for how this was built."
    ),
    "heroes": heroes,
    "matchups": matchups,
    "synergies": synergies,
    "rankWinRates": rank_winrates,
}

json.dump(out, open('hero_data_new.json', 'w'), indent=2)
print("heroes", len(heroes))
print("matchups", len(matchups))
print("synergies", len(synergies))
print("rankWinRates", len(rank_winrates))
