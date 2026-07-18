package com.counterpick.mlbb.network

import com.counterpick.mlbb.data.RankTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Thin client for the community-maintained "OpenMLBB" public API
 * (https://github.com/ridwaanhall/api-mobilelegends), which is the closest thing to a
 * live, no-API-key hero-stats feed for MLBB — Moonton doesn't publish an official one.
 *
 * Endpoint paths below are confirmed against the SDK's actual source
 * (src/OpenMLBB/client.py, MlbbClient class, checked 2026-07-18) — NOT the README's usage
 * examples. That matters: the README shows `client.mlbb.heroes(...)`, which reads like the
 * REST path should be `/mlbb/heroes`, but `mlbb` there is only the Python attribute name.
 * The transport builds the real URL as `{base_url}/{path}` and every MlbbClient method
 * passes a path with NO `/mlbb` prefix — e.g. `heroes()` hits `/heroes`, `heroes_rank()`
 * hits `/heroes/rank` (singular), `hero_counters(id)` hits `/heroes/{id}/counters`. An
 * earlier version of this file had the `/mlbb/...` prefix and the plural `/heroes/ranks`,
 * both wrong, which meant every "live" call was 404ing and silently falling back to
 * offline data the whole time.
 *
 * What's still unverified: the client.py transport just forwards **params straight to
 * requests' `params=`, so the query-param names below (`rank`, `days`, `page_size`,
 * `sort_field`) and the JSON response field names are this file's best guess, not
 * confirmed from source. Parsing stays defensive (multiple candidate key names per field)
 * so a wrong guess there fails closed to the offline fallback in StatsRepository instead
 * of crashing — but if live data still doesn't come through after this path fix, check
 * https://mlbb.rone.dev/api/docs for the actual param/response schema next.
 */
object OpenMlbbClient {

    // "Recommended for 500+ requests per day" per the project README; falls back to
    // the lighter mlbb.rone.dev host automatically if this one errors out.
    private val BASE_HOSTS = listOf(
        "https://openmlbb.fastapicloud.dev/api",
        "https://mlbb.rone.dev/api"
    )

    private const val TIMEOUT_MS = 6000

    private fun rankParam(rank: RankTier): String = when (rank) {
        RankTier.WARRIOR_ELITE -> "elite"
        RankTier.MASTER_GRANDMASTER -> "master"
        RankTier.EPIC -> "epic"
        RankTier.LEGEND -> "legend"
        RankTier.MYTHIC -> "mythic"
        RankTier.MYTHICAL_HONOR_GLORY -> "glory"
    }

    /** Full remote roster: normalized-name -> remote hero id. Changes rarely (new hero patches). */
    suspend fun fetchHeroIndex(): Map<String, Int> = get("/heroes") { root ->
        val arr = firstArray(root, "heroes", "data", "items", "results") ?: (root as? JSONArray)
        val out = mutableMapOf<String, Int>()
        arr?.let { forEachObject(it) { obj ->
            val id = firstInt(obj, "id", "hero_id", "heroId")
            val name = firstString(obj, "name", "hero_name", "heroName")
            if (id != null && name != null) out[normalize(name)] = id
        } }
        out
    } ?: emptyMap()

    /** Bulk win rate for every hero at [rank] in one call — this is what makes "best pick"
     *  scoring for the whole roster affordable to run live instead of one call per hero. */
    suspend fun fetchBulkWinRates(rank: RankTier): Map<Int, Double> =
        get("/heroes/rank?rank=${rankParam(rank)}&days=7&page_size=200&sort_field=win_rate") { root ->
            val arr = firstArray(root, "heroes", "data", "items", "results") ?: (root as? JSONArray)
            val out = mutableMapOf<Int, Double>()
            arr?.let { forEachObject(it) { obj ->
                val id = firstInt(obj, "id", "hero_id", "heroId", "main_heroid")
                val wr = firstNumber(obj, "win_rate", "winRate", "main_hero_win_rate")
                if (id != null && wr != null) out[id] = normalizeRate(wr)
            } }
            out
        } ?: emptyMap()

    /** Heroes that counter (beat) [remoteHeroId] — i.e. good picks *against* them. */
    suspend fun fetchCounters(remoteHeroId: Int, rank: RankTier): List<Pair<Int, Double>> =
        get("/heroes/$remoteHeroId/counters?rank=${rankParam(rank)}&days=7") { root ->
            val arr = firstArray(root, "counters", "counter_heroes", "strong_against", "data")
            parseIdRateList(arr)
        } ?: emptyList()

    /** Heroes that pair well with (raise the win rate of) [remoteHeroId]. */
    suspend fun fetchCompatibility(remoteHeroId: Int, rank: RankTier): List<Pair<Int, Double>> =
        get("/heroes/$remoteHeroId/compatibility?rank=${rankParam(rank)}&days=7") { root ->
            val arr = firstArray(root, "compatible_heroes", "synergies", "compatibility", "data")
            parseIdRateList(arr)
        } ?: emptyList()

    private fun parseIdRateList(arr: JSONArray?): List<Pair<Int, Double>> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Pair<Int, Double>>()
        forEachObject(arr) { obj ->
            val id = firstInt(obj, "id", "hero_id", "heroId")
            val wr = firstNumber(obj, "win_rate", "winRate", "delta", "win_rate_delta")
            if (id != null && wr != null) out += id to normalizeRate(wr)
        }
        return out
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
                        conn.disconnect()
                        break // this host answered but not with success; don't retry it, try the next host
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    val trimmed = body.trim()
                    val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
                    return@withContext parse(root)
                } catch (t: Throwable) {
                    // Covers UnknownHostException (DNS failure), timeouts, connection resets, and
                    // malformed-response parse errors alike. If we have another attempt left for
                    // this host, wait briefly and retry; a lookup that just failed once often
                    // succeeds a moment later. Otherwise fall through to the next host.
                    if (attempt < ATTEMPTS_PER_HOST) delay(RETRY_DELAY_MS) else break
                }
            }
        }
        null
    }

    // -- tiny defensive JSON helpers --------------------------------------------------

    private fun firstArray(obj: Any, vararg keys: String): JSONArray? {
        val o = obj as? JSONObject ?: return null
        for (k in keys) {
            val v = o.opt(k)
            if (v is JSONArray) return v
        }
        return null
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = obj.opt(k)
            if (v is String && v.isNotBlank()) return v
        }
        return null
    }

    private fun firstInt(obj: JSONObject, vararg keys: String): Int? {
        for (k in keys) {
            val v = obj.opt(k)
            when (v) {
                is Int -> return v
                is Number -> return v.toInt()
                is String -> v.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun firstNumber(obj: JSONObject, vararg keys: String): Double? {
        for (k in keys) {
            val v = obj.opt(k)
            when (v) {
                is Number -> return v.toDouble()
                is String -> v.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    /** APIs sometimes report rates as 0..1 and sometimes as 0..100 — normalize to 0..1. */
    private fun normalizeRate(v: Double): Double = if (v > 1.0) v / 100.0 else v

    private fun forEachObject(arr: JSONArray, action: (JSONObject) -> Unit) {
        for (i in 0 until arr.length()) {
            (arr.opt(i) as? JSONObject)?.let(action)
        }
    }

    private fun normalize(name: String): String =
        name.lowercase().trim().replace(Regex("[^a-z0-9]"), "")
}
