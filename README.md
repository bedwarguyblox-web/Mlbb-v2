# MLBB Counter Pick

Draft-phase assistant with two ways to use it:

1. **Manual Draft** (recommended, works instantly) — tap heroes into your team's
   picks, the enemy's picks, and the ban list yourself on a phone-sized screen,
   and get a live-updating, ranked list of your best next pick. No setup required.
2. **Live Overlay** — while you're on the pick/ban screen, it screen-captures
   your own device, identifies which heroes are picked/banned via icon matching,
   and shows the same ranked recommendations in a floating overlay. Needs the
   one-time OpenCV/icon-template/calibration setup described below.

Both share the same 127-hero roster and recommendation engine, factoring in
counters vs the enemy lineup, synergy with your allies, role coverage, and
baseline win rate for the rank you select.

**Use this at the draft/pick-ban screen, not to read a live match in real time.**

## Features

- **Manual draft board** — tap-to-assign ally/enemy picks and bans, no screen
  capture needed. Tap a filled slot to reassign or clear it.
- **Search + role filters** — chips for Tank/Fighter/Assassin/Mage/Marksman/
  Support plus a text search, so finding one hero in 127 is fast on a phone.
- **Favorites** — star heroes you main; they sort to the top of the picker and
  filter with the ★ Favorites chip.
- **Rank-aware recommendations** — baseline win rate, counter advantage, synergy
  advantage, and a role-gap bonus, each shown as a plain-language reason.
- **127-hero roster** sourced from MLBB's official roster / wiki mirror — see
  `DATA_SOURCE_PROMPT.md` for how it was built and how to refresh it each patch.
- **Live overlay mode** — the original screen-capture auto-detect flow, kept as
  a second tab for anyone who's done the OpenCV setup.

## Architecture

- `ui/MainActivity` — two-tab phone UI (Manual Draft / Live Overlay): rank
  chips, hero search/filter/favorites, the draft board, recommendations list,
  and the capture permission flow for Live Overlay.
- `capture/ScreenCaptureService` — foreground service holding the `MediaProjection`,
  grabs a frame roughly every 1.2s (draft is turn-based, no need for higher rate),
  runs it through the scanner + engine, publishes results.
- `vision/DraftLayoutConfig` — normalized (0..1) rectangles for the 10 pick slots
  and ban strip. Calibratable — see below.
- `vision/HeroIconMatcher` — OpenCV `matchTemplate` (TM_CCOEFF_NORMED) against
  hero icon crops you provide.
- `vision/DraftScanner` — crops each configured ROI out of the captured frame and
  resolves it to a hero id via the matcher.
- `engine/RecommendationEngine` — scores every unpicked/unbanned hero:
  `baseWinRate(rank) + 1.4 * avgCounterDelta(vs enemies) + 0.8 * avgSynergyDelta(with allies) + roleNeedBonus`.
- `engine/DraftEventBus` — in-process `StateFlow` connecting the capture service
  to the overlay (both run in the same process, so no AIDL/IPC needed).
- `overlay/OverlayService` — draggable floating panel (`TYPE_APPLICATION_OVERLAY`)
  listing ranked picks with estimated win %.
- `data/` — Room database (`heroes`, `matchups`, `synergies`, `rank_winrates`)
  seeded from `assets/hero_data.json` on first launch.

## Required setup before this builds/runs correctly

1. **OpenCV.** Download the OpenCV Android SDK from opencv.org/releases, unzip it
   next to this project, then in `settings.gradle` uncomment and point the
   `:opencv` include at `<opencv-sdk>/sdk/java`. This is the standard supported
   way to use OpenCV on Android (no reliable Maven Central artifact exists).

2. **Hero icon templates.** `assets/hero_icons/` is empty. For each hero you want
   detected, crop a clean square screenshot of its portrait as it appears on the
   draft screen and save it as `assets/hero_icons/<iconKey>.png` (iconKey values
   are in `hero_data.json`, e.g. `chou.png`, `lancelot.png`). Matching degrades
   gracefully for any hero without a template — it's just never detected.

3. **Calibrate slot positions.** `DraftLayoutConfig.default()` has reasonable
   guesses for a standard landscape draft screen (enemy picks top row, ally picks
   bottom row, bans in a strip). Real MLBB client layout varies by version/device
   aspect ratio. After first run, edit the app's internal `draft_layout.json`
   (`/data/data/com.counterpick.mlbb/files/draft_layout.json` on a rooted/debug
   device, or push a new one via `adb push`) with the correct normalized
   rectangles for your device, using a draft-screen screenshot to eyeball the
   fractions.

4. **Stats data.** `assets/hero_data.json` ships with the full 127-hero roster
   (names/roles/ids) but only an illustrative matchup/synergy/winrate matrix, not
   live scraped stats — see `DATA_SOURCE_PROMPT.md` for exactly how the roster
   was built and a ready-to-reuse prompt for refreshing both the roster and the
   stats each patch. Heroes without stats rows still work in Manual Draft and
   Live Overlay alike, they just default to a neutral 0.50 baseline with no
   counter/synergy data until you add rows.

## Build & run

`gradle-wrapper.properties` is included but the wrapper jar/scripts aren't (no
network access on this end to fetch the binary). One-time, with any local Gradle
install:

```
gradle wrapper --gradle-version 8.7
```

Then normally:

```
./gradlew installDebug
```

On first launch the app opens straight into **Manual Draft** — pick your rank,
tap heroes to assign them, and recommendations update as you go. Nothing else to
set up.

To use **Live Overlay** instead: switch tabs, grant "draw over other apps" when
prompted, tap **Start overlay**, accept the system screen-capture prompt, then
alt-tab into MLBB. The overlay updates automatically as picks/bans lock in.

## Known limitations

- Live Overlay's slot ROIs are fixed-percentage rectangles calibrated per
  device/orientation — no auto-detection of draft-screen layout changes across
  game updates. Manual Draft has no such limitation.
- Live Overlay's template matching is exact-icon matching, not a trained
  classifier — a hero skin variant on the select screen or a UI restyle can
  require a fresh template.
- Rank is manually selected in both modes, not OCR'd, since it's static per
  session and far more reliable that way.
- The stats matrix (win rates, counters, synergies) is illustrative until you
  fill it in from a real source — see `DATA_SOURCE_PROMPT.md`.
