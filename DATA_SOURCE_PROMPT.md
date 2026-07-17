# Refreshing hero_data.json from MLBB's own data each patch

`assets/hero_data.json` is the app's entire "database" — the roster, every matchup
delta, every synergy delta, and every rank-bracket win rate come from that one file
(see `SeedDataLoader`). Moonton's official site (`mobilelegends.com`) is a
JavaScript-rendered marketing site with no public hero API, so there's no single
URL to scrape for live stats. What *is* reliably scrapable is the **roster itself**
(names, roles, release order) from the official site's hero pages and the
community wiki that mirrors it 1:1. Win rates and matchup numbers change every
patch and aren't published by Moonton at all — those have to come from a stats
tracker or your own match history, same as the README already says.

So the practical workflow is two separate refreshes:

## 1. Roster refresh (names, roles, new heroes) — fully automatable

This repo's current `hero_data.json` (127 heroes) was built by fetching
**`https://mobile-legends.fandom.com/wiki/List_of_heroes`**, which mirrors the
official site's hero roster (role, specialty, lane, release date) in one table
and is far easier to parse than the JS-rendered `mobilelegends.com` pages. Re-run
this prompt with an AI assistant that has web search/fetch (like this one) once
a season to pick up new hero releases:

> Fetch `https://mobile-legends.fandom.com/wiki/List_of_heroes` and list every
> hero currently missing from the attached `hero_data.json` (compare by name).
> For each new hero give: name, primary role, secondary role if any (from the
> wiki's "Role(s)" column), and a lowercase `snake_case` iconKey with no
> punctuation. Assign each a new `id` inside its primary role's block
> (Tank 100–199, Fighter 200–299, Assassin 300–399, Mage 400–499,
> Marksman 500–599, Support 600–699) continuing from the highest id already
> used in that block. Output only the new entries as JSON objects matching the
> existing `heroes` array's shape, ready to append.

Append the result to the `heroes` array in `hero_data.json`. Existing hero ids
never change, so this never breaks rows already in `matchups`/`synergies`/
`rankWinRates`.

## 2. Stats refresh (win rates, matchup/synergy deltas) — needs a stats source

There's no official numbers feed for this, so pick one:

- **A public stats tracker's hero/matchup pages** (several exist and publish
  per-patch win rate, pick rate, and counter tables by rank bracket) — fetch the
  page for each hero you care about and transcribe the numbers.
- **Your own post-match history**, if the tracker you use exports it.

Either way, this prompt turns raw numbers into the right JSON shape:

> Here are win-rate/matchup numbers for `<hero>` at `<rank>` from `<source>`:
> `<paste the numbers>`. Convert them into `rankWinRates` rows (`heroId`, `rank`
> as one of WARRIOR_ELITE / MASTER_GRANDMASTER / EPIC / LEGEND / MYTHIC /
> MYTHICAL_HONOR_GLORY, `winRate` as a 0–1 fraction) and `matchups` rows
> (`heroId`, `versusHeroId`, `winRateDelta` as the signed swing in `<hero>`'s
> favor) using the hero ids in the attached `hero_data.json`. Only emit rows for
> matchups actually present in the source data — don't invent numbers.

You don't have to cover every hero — `RecommendationEngine` falls back to a
neutral 0.50 baseline and zero matchup/synergy delta for anything you haven't
filled in, so partial data degrades gracefully rather than breaking.

## Why not fetch mobilelegends.com directly at runtime?

Baking stats into a shipped JSON (rather than having the app call a live API)
keeps the overlay usable with zero network latency mid-draft, works if MLBB's
own network traffic is the only thing the phone can reach, and means one bad
scrape can't silently corrupt recommendations during a ranked match. It costs
you a manual refresh step each patch — worth it for a tool you're relying on
in real time.
