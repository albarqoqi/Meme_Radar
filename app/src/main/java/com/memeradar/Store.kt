package com.memeradar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("radar", Context.MODE_PRIVATE)

    fun isEnabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun cfg(c: Context): Cfg {
        val s = sp(c)
        return Cfg(
            chains = s.getString("chains", "solana,base")!!.split(",").filter { it.isNotBlank() },
            alertScore = s.getFloat("score", 70f).toDouble(),
            pollSeconds = s.getInt("poll", 45),
        )
    }
    fun saveCfg(c: Context, chains: List<String>, score: Double, poll: Int) =
        sp(c).edit().putString("chains", chains.joinToString(",")).putFloat("score", score.toFloat())
            .putInt("poll", poll).apply()

    fun setStatus(c: Context, s: String) = sp(c).edit().putString("status", s).apply()
    fun status(c: Context) = sp(c).getString("status", "Stopped")!!

    @Synchronized
    fun addSignal(c: Context, x: Cand) {
        val arr = JSONArray(sp(c).getString("signals", "[]"))
        val o = JSONObject().put("t", System.currentTimeMillis()).put("sym", x.symbol).put("chain", x.chain)
            .put("score", x.score).put("mcap", x.mcap).put("liq", x.liq).put("age", x.ageMin)
            .put("h1", x.chH1).put("url", x.url).put("sec", x.secNotes.joinToString("; "))
            .put("flags", x.flags.joinToString(","))
        val out = JSONArray().put(o)
        for (i in 0 until minOf(arr.length(), 99)) out.put(arr.get(i))
        sp(c).edit().putString("signals", out.toString()).apply()
    }
    fun signals(c: Context): List<JSONObject> {
        val a = JSONArray(sp(c).getString("signals", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it) }
    }
    fun clearSignals(c: Context) = sp(c).edit().remove("signals").apply()
}
