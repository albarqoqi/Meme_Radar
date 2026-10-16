package com.memeradar

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

const val DEX = "https://api.dexscreener.com"
const val GECKO = "https://api.geckoterminal.com/api/v2"
val GECKO_NET = mapOf("solana" to "solana", "base" to "base", "bsc" to "bsc", "ethereum" to "eth")
val GOPLUS_CHAIN = mapOf("ethereum" to "1", "bsc" to "56", "base" to "8453")
val MAJOR_QUOTES = setOf("SOL", "WSOL", "USDC", "USDT", "WETH", "ETH", "WBNB", "BNB")

data class Cfg(
    var chains: List<String> = listOf("solana", "base"),
    var pollSeconds: Int = 45,
    var universeCap: Int = 600,
    var geckoNewPages: Int = 2,
    var minAgeMin: Double = 8.0, var maxAgeMin: Double = 360.0,
    var minLiq: Double = 20_000.0, var minVolH1: Double = 25_000.0,
    var minTxnsH1: Int = 150, var minMcap: Double = 50_000.0, var maxMcap: Double = 5_000_000.0,
    var maxH1Change: Double = 400.0, var minBuyRatio: Double = 0.52, var minLiqToMcap: Double = 0.04,
    var alertScore: Double = 70.0, var confirmCycles: Int = 2, var cooldownMin: Double = 120.0,
    var useSecurity: Boolean = true, var requireSecurity: Boolean = true,
    var maxTaxPct: Double = 8.0, var minLpLockedPct: Double = 50.0,
)

// ---------- helpers ----------
fun clamp(x: Double, lo: Double = 0.0, hi: Double = 1.0) = max(lo, min(hi, x))
fun lerp01(x: Double, a: Double, b: Double) = if (a != b) clamp((x - a) / (b - a)) else 0.0
fun norm(chain: String, addr: String) = if (chain == "solana") addr else addr.lowercase()
fun fmtUsd(x: Double) = when {
    x >= 1e9 -> "$%.2fB".format(x / 1e9)
    x >= 1e6 -> "$%.2fM".format(x / 1e6)
    x >= 1e3 -> "$%.1fK".format(x / 1e3)
    else -> "$%.0f".format(x)
}

/** Walk nested JSON; returns 0.0 if missing. */
fun JSONObject.d(vararg path: String): Double {
    var o: Any? = this
    for (k in path) { o = (o as? JSONObject)?.opt(k) ?: return 0.0 }
    return when (o) { is Number -> o.toDouble(); is String -> o.toDoubleOrNull() ?: 0.0; else -> 0.0 }
}

private val lastCall = HashMap<String, Long>()

fun getJson(url: String, params: Map<String, String> = emptyMap(), tries: Int = 3,
            gapKey: String = "", gapMs: Long = 0): Any? {
    var full = url
    if (params.isNotEmpty()) full += "?" + params.entries.joinToString("&") {
        it.key + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
    for (i in 0 until tries) {
        if (gapKey.isNotEmpty()) {
            val wait = (lastCall[gapKey] ?: 0L) + gapMs - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall[gapKey] = System.currentTimeMillis()
        }
        try {
            val c = URL(full).openConnection() as HttpURLConnection
            c.connectTimeout = 12000; c.readTimeout = 12000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 meme-radar-android/1.0")
            c.setRequestProperty("Accept", "application/json")
            when (c.responseCode) {
                200 -> {
                    val body = c.inputStream.bufferedReader().use { it.readText() }.trim()
                    return if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
                }
                429 -> { Thread.sleep(3000L * (i + 1)); continue }
                404 -> return null
                else -> Thread.sleep(1000L * (i + 1))
            }
        } catch (e: InterruptedException) { throw e } catch (_: Exception) { Thread.sleep(1000L * (i + 1)) }
    }
    return null
}

// ---------- candidate ----------
class Cand(
    val chain: String, val address: String, val symbol: String, val name: String, val url: String,
    val price: Double, val liq: Double, val mcap: Double, val ageMin: Double,
    val volM5: Double, val volH1: Double,
    val buysM5: Int, val sellsM5: Int, val buysH1: Int, val sellsH1: Int,
    val chM5: Double, val chH1: Double, val hasSocials: Boolean,
    var buyersH1: Int? = null, var sellersH1: Int? = null,
) {
    var score = 0.0
    var flags = listOf<String>()
    var secNotes = listOf<String>()
    val key get() = "$chain:${norm(chain, address)}"
    val txnsH1 get() = buysH1 + sellsH1
    val buyRatioH1 get() = if (txnsH1 > 0) buysH1.toDouble() / txnsH1 else 0.0
}

fun toCand(p: JSONObject, nowMs: Long, extra: Map<String, Int?>?): Cand? = try {
    val created = p.optLong("pairCreatedAt", 0)
    val price = p.optString("priceUsd", "0").toDoubleOrNull() ?: 0.0
    val liq = p.d("liquidity", "usd")
    val mcap = if (p.has("marketCap")) p.d("marketCap") else p.d("fdv")
    val quote = p.optJSONObject("quoteToken")?.optString("symbol", "")?.uppercase() ?: ""
    if (created == 0L || price <= 0 || liq <= 0 || mcap <= 0 || quote !in MAJOR_QUOTES) null
    else {
        val base = p.getJSONObject("baseToken")
        val info = p.optJSONObject("info")
        val social = info != null && ((info.optJSONArray("websites")?.length() ?: 0) > 0 ||
                (info.optJSONArray("socials")?.length() ?: 0) > 0)
        Cand(p.getString("chainId"), base.getString("address"), base.optString("symbol", "?"),
            base.optString("name", "?"), p.optString("url"), price, liq, mcap,
            (nowMs - created) / 60000.0,
            p.d("volume", "m5"), p.d("volume", "h1"),
            p.d("txns", "m5", "buys").toInt(), p.d("txns", "m5", "sells").toInt(),
            p.d("txns", "h1", "buys").toInt(), p.d("txns", "h1", "sells").toInt(),
            p.d("priceChange", "m5"), p.d("priceChange", "h1"), social,
            extra?.get("buyers"), extra?.get("sellers"))
    }
} catch (_: Exception) { null }

// ---------- gates + score ----------
fun gate(c: Cand, f: Cfg): String? = when {
    c.ageMin !in f.minAgeMin..f.maxAgeMin -> "age"
    c.liq < f.minLiq -> "liq"
    c.volH1 < f.minVolH1 -> "vol"
    c.txnsH1 < f.minTxnsH1 -> "txns"
    c.mcap !in f.minMcap..f.maxMcap -> "mcap"
    c.chH1 > f.maxH1Change -> "chasing"
    c.buyRatioH1 < f.minBuyRatio -> "buyratio"
    c.liq / c.mcap < f.minLiqToMcap -> "liq/mcap"
    else -> null
}

fun score(c: Cand, f: Cfg): Double {
    val flags = ArrayList<String>()
    val sVol = 25 * lerp01((c.volM5 * 12) / max(c.volH1, 1.0), 0.6, 2.0)
    val m5tx = c.buysM5 + c.sellsM5
    val brM5 = if (m5tx > 0) c.buysM5.toDouble() / m5tx else 0.5
    var br = 0.6 * c.buyRatioH1 + 0.4 * brM5
    val bu = c.buyersH1; val se = c.sellersH1
    if (bu != null && se != null && bu + se > 0) br = 0.7 * br + 0.3 * (bu.toDouble() / (bu + se))
    val sBuy = 20 * lerp01(br, 0.50, 0.66)
    val sBreadth = 10 * lerp01(log10(max(c.txnsH1, 1).toDouble()), log10(150.0), log10(2500.0))
    val sLiq = 15 * lerp01(c.liq / c.mcap, f.minLiqToMcap, 0.15)
    val ch = c.chH1
    val m = when {
        ch <= 0 -> 0.0
        ch < 10 -> 0.5 * ch / 10
        ch <= 120 -> 1.0
        else -> 1.0 - lerp01(ch, 120.0, f.maxH1Change)
    }
    val a = c.ageMin
    val fr = when {
        a < 20 -> lerp01(a, f.minAgeMin, 20.0)
        a <= 120 -> 1.0
        else -> 1.0 - lerp01(a, 120.0, f.maxAgeMin)
    }
    var total = sVol + sBuy + sBreadth + sLiq + 15 * m + 10 * fr + (if (c.hasSocials) 5.0 else 0.0)
    if (c.chM5 < -8) { total -= 15; flags += "dumping-5m" }
    if (c.sellsM5 > 1.5 * max(c.buysM5, 1)) { total -= 10; flags += "sell-wall-5m" }
    if (c.volH1 / max(c.txnsH1, 1) > 0.02 * c.liq) { total -= 10; flags += "oversized-trades" }
    if (c.volH1 / max(c.liq, 1.0) > 40) { total -= 8; flags += "vol/liq>40x" }
    if (!c.hasSocials) flags += "no-socials"
    c.flags = flags
    c.score = Math.round(clamp(total, 0.0, 100.0) * 10) / 10.0
    return c.score
}

// ---------- security ----------
class Security(private val f: Cfg) {
    private val cache = HashMap<String, Pair<Boolean, List<String>>>()

    fun check(c: Cand): Pair<Boolean, List<String>> {
        if (!f.useSecurity) return true to listOf("security disabled")
        cache[c.key]?.let { return it }
        val res = when {
            c.chain == "solana" -> rugcheck(c)
            c.chain in GOPLUS_CHAIN -> goplus(c)
            else -> !f.requireSecurity to listOf("no provider")
        }
        if (res.second.none { it.contains("unavailable") }) cache[c.key] = res
        return res
    }

    private fun rugcheck(c: Cand): Pair<Boolean, List<String>> {
        val j = getJson("https://api.rugcheck.xyz/v1/tokens/${c.address}/report/summary",
            gapKey = "rugcheck", gapMs = 1000) as? JSONObject
            ?: return !f.requireSecurity to listOf("RugCheck unavailable")
        val risks = j.optJSONArray("risks") ?: JSONArray()
        val hard = listOf("mint authority", "freeze authority", "honeypot", "rugged")
        val bad = ArrayList<String>()
        for (i in 0 until risks.length()) {
            val r = risks.getJSONObject(i)
            val name = r.optString("name", "?")
            if (r.optString("level").equals("danger", true) || hard.any { name.lowercase().contains(it) }) bad += name
        }
        if (bad.isNotEmpty()) return false to listOf("RugCheck: " + bad.joinToString("; "))
        val notes = arrayListOf(if (risks.length() > 0) "${risks.length()} minor risk(s)" else "RugCheck clean")
        if (j.has("lpLockedPct")) {
            val lp = j.optDouble("lpLockedPct")
            if (lp < f.minLpLockedPct) return false to listOf("LP locked only %.0f%%".format(lp))
            notes += "LP locked %.0f%%".format(lp)
        }
        return true to notes
    }

    private fun goplus(c: Cand): Pair<Boolean, List<String>> {
        val j = getJson("https://api.gopluslabs.io/api/v1/token_security/${GOPLUS_CHAIN[c.chain]}",
            mapOf("contract_addresses" to c.address.lowercase()), gapKey = "goplus", gapMs = 1000) as? JSONObject
        val d = j?.optJSONObject("result")?.optJSONObject(c.address.lowercase())
            ?: return !f.requireSecurity to listOf("GoPlus unavailable")
        val bad = ArrayList<String>()
        fun on(k: String) = d.optString(k) == "1"
        if (on("is_honeypot")) bad += "honeypot"
        if (on("cannot_sell_all")) bad += "cannot sell all"
        if (on("is_mintable")) bad += "mintable"
        if (on("owner_change_balance")) bad += "owner can change balances"
        if (on("hidden_owner")) bad += "hidden owner"
        if (on("transfer_pausable")) bad += "transfers pausable"
        if (on("is_blacklisted") || on("trading_cooldown")) bad += "blacklist/cooldown"
        if (d.optString("is_open_source") == "0") bad += "unverified source"
        for (k in listOf("buy_tax", "sell_tax")) {
            val t = (d.optString(k).toDoubleOrNull() ?: 0.0) * 100
            if (t > f.maxTaxPct) bad += "$k %.0f%%".format(t)
        }
        return if (bad.isNotEmpty()) false to listOf("GoPlus: " + bad.joinToString("; "))
        else true to listOf("GoPlus clean")
    }
}

// ---------- engine ----------
class Radar(val cfg: Cfg) {
    private class U(val chain: String, val addr: String, val first: Long = System.currentTimeMillis()) {
        val extra = HashMap<String, Int?>(); var miss = 0
    }
    private val universe = HashMap<String, U>()
    private val streak = HashMap<String, Int>()
    private val alerted = HashMap<String, Long>()
    private val sec = Security(cfg)
    var universeSize = 0; private set
    var qualifiedCount = 0; private set
    var top: Cand? = null; private set

    private fun add(chain: String, addr: String, extra: Map<String, Int?>? = null) {
        val u = universe.getOrPut("$chain:$addr") { U(chain, addr) }
        extra?.forEach { (k, v) -> if (v != null) u.extra[k] = v }
    }

    private fun discover() {
        for (chain in cfg.chains) {
            val net = GECKO_NET[chain] ?: continue
            val urls = (1..cfg.geckoNewPages).map { "$GECKO/networks/$net/new_pools" to mapOf("page" to "$it") } +
                    ("$GECKO/networks/$net/trending_pools" to emptyMap())
            for ((url, params) in urls) {
                val data = (getJson(url, params, gapKey = "gecko", gapMs = 2200) as? JSONObject)?.optJSONArray("data") ?: continue
                for (i in 0 until data.length()) try {
                    val it = data.getJSONObject(i)
                    val id = it.getJSONObject("relationships").getJSONObject("base_token")
                        .getJSONObject("data").getString("id")
                    val addr = id.substringAfter("_")
                    val tx = it.optJSONObject("attributes")?.optJSONObject("transactions")?.optJSONObject("h1")
                    add(chain, norm(chain, addr), mapOf(
                        "buyers" to tx?.optInt("buyers", -1)?.takeIf { v -> v >= 0 },
                        "sellers" to tx?.optInt("sellers", -1)?.takeIf { v -> v >= 0 }))
                } catch (_: Exception) {}
            }
        }
        for (path in listOf("/token-profiles/latest/v1", "/token-boosts/latest/v1", "/token-boosts/top/v1")) {
            val arr = getJson(DEX + path, gapKey = "dex60", gapMs = 1100) as? JSONArray ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val ch = o.optString("chainId"); val ad = o.optString("tokenAddress")
                if (ch in cfg.chains && ad.isNotEmpty()) add(ch, norm(ch, ad))
            }
        }
        if (universe.size > cfg.universeCap) {
            universe.entries.sortedBy { it.value.first }.take(universe.size - cfg.universeCap)
                .forEach { universe.remove(it.key) }
        }
    }

    private fun refresh(): List<Cand> {
        val now = System.currentTimeMillis()
        val best = HashMap<String, Cand>()
        for ((chain, us) in universe.values.groupBy { it.chain }) {
            val addrs = us.map { it.addr }
            for (batch in addrs.chunked(30)) {
                val arr = getJson("$DEX/tokens/v1/$chain/${batch.joinToString(",")}",
                    gapKey = "dex300", gapMs = 250) as? JSONArray ?: continue
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val ch = p.optString("chainId", chain)
                    val a = norm(ch, p.optJSONObject("baseToken")?.optString("address") ?: continue)
                    val c = toCand(p, now, universe["$ch:$a"]?.extra) ?: continue
                    if (c.key !in best || c.liq > best[c.key]!!.liq) best[c.key] = c
                }
            }
        }
        val it = universe.entries.iterator()
        while (it.hasNext()) {
            val (k, u) = it.next()
            val c = best[k]
            if (c != null) { u.miss = 0; if (c.ageMin > cfg.maxAgeMin) it.remove() }
            else if (++u.miss >= 6) it.remove()
        }
        return best.values.toList()
    }

    /** One full pass. Returns NEW confirmed + safety-checked signals. */
    fun cycle(): List<Cand> {
        discover()
        val cands = refresh()
        universeSize = universe.size
        val q = ArrayList<Cand>()
        for (c in cands) {
            if (gate(c, cfg) != null) { streak[c.key] = 0; continue }
            score(c, cfg)
            streak[c.key] = if (c.score >= cfg.alertScore) (streak[c.key] ?: 0) + 1 else 0
            q += c
        }
        q.sortByDescending { it.score }
        qualifiedCount = q.size; top = q.firstOrNull()
        val out = ArrayList<Cand>()
        for (c in q) {
            if (c.score < cfg.alertScore || (streak[c.key] ?: 0) < cfg.confirmCycles) continue
            if (System.currentTimeMillis() - (alerted[c.key] ?: 0) < cfg.cooldownMin * 60_000) continue
            val (ok, notes) = sec.check(c)
            c.secNotes = notes
            alerted[c.key] = System.currentTimeMillis()
            if (ok) out += c
        }
        return out
    }
}
