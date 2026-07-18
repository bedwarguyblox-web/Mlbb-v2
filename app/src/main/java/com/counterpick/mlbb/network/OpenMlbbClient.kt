package com.counterpick.mlbb.network

import com.counterpick.mlbb.data.RankTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thin client for the community-maintained "OpenMLBB" public API
 * (https://github.com/ridwaanhall/api-mobilelegends), which is the closest thing to a
 * live, no-API-key hero-stats feed for MLBB — Moonton doesn't publish an official one.
 *
 * Endpoint paths, query params, and response shape below are all confirmed directly
 * against the FastAPI server source (app/api/routers/mlbb.py, checked 2026-07-18) — the
 * actual router definitions, not the SDK or README, which is why earlier versions of this
 * file kept guessing wrong: the response envelope is deeply nested and doesn't look
 * anything like a flat `{heroes: [{id, name}]}` list.
 *
 * The real shape, for every one of these endpoints, is:
 *   { "code": 0, "message": "OK", "data": { "records": [ ... ], "total": N } }
 * — with each record's actual payload one level further in, under a `data` key on the
 * record itself. Concretely:
 *   - GET /heroes:                record.data.hero_id (int), record.data.hero.data.name (string)
 *   - GET /heroes/rank:           record.data.main_heroid (int), record.data.main_hero.data.name,
 *                                 record.data.main_hero_win_rate (double, already 0..1)
 *   - GET /heroes/{id}/counters:      record.data.sub_hero[] -> {heroid, increase_win_rate}
 *   - GET /heroes/{id}/compatibility: record.data.sub_hero[] -> {heroid, increase_win_rate}
 * (counters/compatibility responses normally contain exactly one record — the queried
 * hero's own stat line — whose sub_hero array is the actual list of other heroes wanted.)
 *
 * Also confirmed from source (app/core/enums.py RankEnum): valid `rank` query values are
 * only `all`, `epic`, `legend`, `mythic`, `honor`, `glory` — there is no live-API rank
 * bucket below `epic`. This app's WARRIOR_ELITE and MASTER_GRANDMASTER tiers have no real
 * counterpart, so both fall back to `all` (aggregate across all ranks) rather than
 * guessing at a nonexistent bucket. Likewise MYTHICAL_HONOR_GLORY collapses two distinct
 * real values (`honor` and `glory`) into one — this maps to `glory` (the higher of the
 * two) since that's the closer read of what that combined label usually means in MLBB
 * community shorthand. If that turns out backwards for how you use it, `rankParam` below
 * is the one place to adjust.
 */
object OpenMlbbClient {

    // "Recommended for 500+ requests per day" per the project README; falls back to
    // the lighter mlbb.rone.dev host automatically if this one errors out.
    private val BASE_HOSTS = listOf(
        "https://openmlbb.fastapicloud.dev/api",
        "https://mlbb.rone.dev/api"
    )

    private const val TIMEOUT_MS = 6000

    /**
     * Human-readable account of what happened on the most recent [get] call — which host
     * answered (if any), the HTTP status, and how many usable records came back. A request
     * can succeed at the HTTP layer and still return zero usable data if a response field
     * turns out not to match what's documented (schemas drift); read this after a call to
     * tell that apart from an actual network problem. Not thread-safe against concurrent
     * calls, which is fine — it's a best-effort diagnostic, not something scored on.
     */
    @Volatile var lastDiagnostic: String = "no request made yet"
        private set

    private fun rankParam(rank: RankTier): String = when (rank) {
        RankTier.WARRIOR_ELITE -> "all"
        RankTier.MASTER_GRANDMASTER -> "all"
        RankTier.EPIC -> "epic"
        RankTier.LEGEND -> "legend"
        RankTier.MYTHIC -> "mythic"
        RankTier.MYTHICAL_HONOR_GLORY -> "glory"
    }

    /** Full remote roster: normalized-name -> remote hero id. Changes rarely (new hero patches). */
    suspend fun fetchHeroIndex(): Map<String, Int> {
        val out = get("/heroes?size=200") { root ->
            val map = mutableMapOf<String, Int>()
            forEachRecord(root) { recordData ->
                val id = recordData.optInt("hero_id", -1)
                val name = recordData.optJSONObject("hero")?.optJSONObject("data")?.optString("name")
                if (id >= 0 && !name.isNullOrBlank()) map[normalize(name)] = id
            }
            map
        } ?: emptyMap()
        // A request can come back HTTP 200 with a real body and still yield zero usable
        // heroes if a response field doesn't match what's expected — that's indistinguishable
        // from a network failure in the UI unless we say so here.
        lastDiagnostic += " | parsed ${out.size} heroes from response"
        return out
    }

    /** Bulk win rate for every hero at [rank] in one call — this is what makes "best pick"
     *  scoring for the whole roster affordable to run live instead of one call per hero. */
    suspend fun fetchBulkWinRates(rank: RankTier): Map<Int, Double> {
        val out = get("/heroes/rank?rank=${rankParam(rank)}&days=7&size=200&sort_field=win_rate") { root ->
            val map = mutableMapOf<Int, Double>()
            forEachRecord(root) { recordData ->
                val id = recordData.optInt("main_heroid", -1)
                val wr = recordData.optDouble("main_hero_win_rate", Double.NaN)
                if (id >= 0 && !wr.isNaN()) map[id] = normalizeRate(wr)
            }
            map
        } ?: emptyMap()
        lastDiagnostic += " | parsed ${out.size} win rates from response"
        return out
    }

    /** Heroes that counter (beat) [remoteHeroId] — i.e. good picks *against* them. */
    suspend fun fetchCounters(remoteHeroId: Int, rank: RankTier): List<Pair<Int, Double>> =
        get("/heroes/$remoteHeroId/counters?rank=${rankParam(rank)}&days=7") { root ->
            parseSubHeroList(root)
        } ?: emptyList()

    /** Heroes that pair well with (raise the win rate of) [remoteHeroId]. */
    suspend fun fetchCompatibility(remoteHeroId: Int, rank: RankTier): List<Pair<Int, Double>> =
        get("/heroes/$remoteHeroId/compatibility?rank=${rankParam(rank)}&days=7") { root ->
            parseSubHeroList(root)
        } ?: emptyList()

    /** Shared by counters/compatibility: both endpoints return one or more records, each
     *  with a data.sub_hero[] array of {heroid, increase_win_rate}. Merge across every
     *  record returned rather than assuming exactly one, just in case a query ever matches
     *  more than the usual single stat line. */
    private fun parseSubHeroList(root: Any): List<Pair<Int, Double>> {
        val out = mutableListOf<Pair<Int, Double>>()
        forEachRecord(root) { recordData ->
            val subHeroes = recordData.optJSONArray("sub_hero") ?: return@forEachRecord
            forEachObject(subHeroes) { sub ->
                val id = sub.optInt("heroid", -1)
                val delta = sub.optDouble("increase_win_rate", Double.NaN)
                if (id >= 0 && !delta.isNaN()) out += id to normalizeRate(delta)
            }
        }
        return out
    }

    /** Walks root.data.records[], handing each record's inner `data` object to [action].
     *  Every endpoint on this API wraps its actual payload this way. */
    private fun forEachRecord(root: Any, action: (JSONObject) -> Unit) {
        val records = (root as? JSONObject)?.optJSONObject("data")?.optJSONArray("records") ?: return
        forEachObject(records) { record ->
            record.optJSONObject("data")?.let(action)
        }
    }

    // -- transport ------------------------------------------------------------------

    // Per-host attempts before moving on. A single failed DNS lookup or dropped packet
    // is common on flaky mobile connections and doesn't mean the host is actually down —
    // retrying once with a short delay recovers most of those without waiting for a
    // second host's full timeout. Anything that fails twice in a row here is treated as
    // "this host is genuinely unreachable right now" and we move on.
    private const val ATTEMPTS_PER_HOST = 2
    private const val RETRY_DELAY_MS = 400L

    private suspend fun <T> get(path: String, parse: (Any) -> T): T? = withContext(Dispatchers.IO) {
        val attemptLog = mutableListOf<String>()
        for (host in BASE_HOSTS) {
            for (attempt in 1..ATTEMPTS_PER_HOST) {
                try {
                    val url = URL(host + path)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = TIMEOUT_MS
                        readTimeout = TIMEOUT_MS
                        setRequestProperty("Accept", "application/json")
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        attemptLog += "$host$path -> HTTP $code"
                        conn.disconnect()
                        break // this host answered but not with success; don't retry it, try the next host
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val trimmed = body.trim()
                    val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
                    val result = parse(root)
                    attemptLog += "$host$path -> HTTP $code, ${trimmed.length} bytes, parsed OK"
                    lastDiagnostic = attemptLog.joinToString(" | ")
                    return@withContext result
                } catch (t: Throwable) {
                    // Covers UnknownHostException (DNS failure), timeouts, connection resets, and
                    // malformed-response parse errors alike. If we have another attempt left for
                    // this host, wait briefly and retry; a lookup that just failed once often
                    // succeeds a moment later. Otherwise fall through to the next host.
                    attemptLog += "$host$path -> ${t.javaClass.simpleName}: ${t.message}"
                    if (attempt < ATTEMPTS_PER_HOST) delay(RETRY_DELAY_MS) else break
                }
            }
        }
        lastDiagnostic = if (attemptLog.isEmpty()) "no hosts attempted" else attemptLog.joinToString(" | ")
        null
    }

    // -- tiny JSON helpers ------------------------------------------------------------

    private fun forEachObject(arr: JSONArray, action: (JSONObject) -> Unit) {
        for (i in 0 until arr.length()) {
            (arr.opt(i) as? JSONObject)?.let(action)
        }
    }

    /** The API's win rates and deltas are already 0..1 fractions (e.g. 0.506002), but
     *  normalize defensively in case a field ever comes back as a 0..100 percentage. */
    private fun normalizeRate(v: Double): Double = if (v > 1.0 || v < -1.0) v / 100.0 else v

    private fun normalize(name: String): String =
        name.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
}
