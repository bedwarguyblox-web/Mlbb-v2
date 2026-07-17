# MLBB Counter Pick

Draft-phase assistant: while you're on the pick/ban screen, it screen-captures your
own device, identifies which heroes are picked/banned via icon matching, and shows
a floating overlay ranking your best next pick — factoring in counters vs the
enemy lineup, synergy with your allies, role coverage, and baseline win rate for
the rank you select.

**Use this at the draft/pick-ban screen, not to read a live match in real time.**

## Architecture

- `ui/MainActivity` — rank picker, permission flow, starts/stops capture.
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

4. **Stats data.** `assets/hero_data.json` ships with a representative but
   illustrative matchup/synergy/winrate matrix, not live scraped stats. Update it
   each patch — add rows for `matchups`, `synergies`, and `rankWinRates` for any
   hero pairing you care about. Unlisted heroes still work, they just default to
   a neutral 0.50 baseline with no counter/synergy data until you add rows.

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

On first launch: grant "draw over other apps" when prompted, pick your rank,
tap **Start overlay**, accept the system screen-capture prompt, then alt-tab into
MLBB. The overlay updates automatically as picks/bans lock in.

## Known limitations

- Slot ROIs are fixed-percentage rectangles calibrated per device/orientation —
  no auto-detection of draft-screen layout changes across game updates.
- Template matching is exact-icon matching, not a trained classifier — a hero
  skin variant on the select screen or a UI restyle can require a fresh template.
- Rank is manually selected, not OCR'd, since it's static per session and far
  more reliable that way.
