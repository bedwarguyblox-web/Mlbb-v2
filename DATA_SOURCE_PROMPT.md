# Keeping the roster and offline fallback current

Since the live-data rework, `assets/hero_data.json` is no longer the app's
"database" — win rates, counters, and synergies are fetched at calculation time
from the [OpenMLBB API](https://github.com/ridwaanhall/api-mobilelegends) via
`network/OpenMlbbClient.kt` and `data/StatsRepository.kt`. There's nothing to
manually refresh each patch for those numbers anymore.

Two things in `hero_data.json` are still worth an occasional touch-up:

## 1. Roster (names, roles, new heroes) — still hand-maintained, and still automatable

`iconKey` maps to bundled art under `assets/hero_icons/` that no API can supply,
so the roster itself stays a local list. Refresh it the same way as before, once
a season or after a big hero-release wave:

> Fetch `https://mobile-legends.fandom.com/wiki/List_of_heroes` and list every
> hero currently missing from the attached `hero_data.json` (compare by name).
> For each new hero give: name, primary role, secondary role if any (from the
> wiki's "Role(s)" column), and a lowercase `snake_case` iconKey with no
> punctuation. Assign each a new `id` inside its primary role's block
> (Tank 100–199, Fighter 200–299, Assassin 300–399, Mage 400–499,
> Marksman 500–599, Support 600–699) continuing from the highest id already
> used in that block. Output only the new entries as JSON objects matching the
> existing `heroes` array's shape, ready to append.

A hero missing from the local roster just never shows up as a pickable chip —
it doesn't affect anyone else's live stats fetch, since those are keyed off the
OpenMLBB roster, joined to the local one by name at runtime.

## 2. Offline fallback snapshot (`matchups`/`synergies`/`rankWinRates`) — safety net only

`StatsRepository` only ever reads these when a live fetch genuinely fails (no
network, API down, unexpected response shape) or hasn't completed yet for a
hero that isn't locked into the draft. Because it's a fallback rather than the
primary path, it doesn't need per-patch accuracy — "reasonable and not
embarrassingly stale" is enough. Refresh it with a prompt like:

> Here's a current MLBB tier list / meta write-up: `<paste or link>`. Update
> the `rankWinRates` baseline offsets and the `matchups`/`synergies` groups in
> `gen_data.py`-style curated lists for `hero_data.json`, keeping the existing
> shape (`heroId`, `versusHeroId`/`withHeroId`, `winRateDelta` as a signed 0–1
> swing). Only add rows for relationships the source actually supports — leave
> everything else on the neutral fallback.

## Why keep an offline fallback at all, if the data's live now?

A live network call can fail mid-draft (bad stadium wifi, the free API rate-
limiting, a temporary outage) and a counter-pick tool that goes blank at that
exact moment is worse than useless. `StatsRepository` always has *something* to
score against — live data when it can get it, this snapshot when it can't —
so a flaky connection degrades the recommendations' accuracy instead of
breaking the app.

## Verifying the live endpoints

`network/OpenMlbbClient.kt` has a comment at the top flagging that its endpoint
paths/field names came from OpenMLBB's README and SDK method signatures, not a
directly-fetched OpenAPI schema (that host's `robots.txt` blocks automated
fetches). If recommendations look stuck on fallback data, check
`https://mlbb.rone.dev/api/docs` (Swagger UI) against the path constants and
JSON keys in that file first — that's the most likely place a small mismatch
would live.
