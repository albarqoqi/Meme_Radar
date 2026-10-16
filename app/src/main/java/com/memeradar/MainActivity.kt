package com.memeradar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var list: LinearLayout
    private val chainBoxes = LinkedHashMap<String, CheckBox>()
    private lateinit var scoreIn: EditText
    private lateinit var pollIn: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun tv(t: String, sp: Float, color: Int = 0xFFE6EDF3.toInt()) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(8)) }
        root.addView(tv("📡 Meme Radar", 24f))
        status = tv("", 13f, 0xFF8B98A5.toInt()).apply { setPadding(0, dp(6), 0, dp(10)) }
        root.addView(status)

        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        root.addView(toggle)

        val cfg = Store.cfg(this)
        val chainRow = LinearLayout(this)
        for (c in listOf("solana", "base", "bsc", "ethereum")) {
            val cb = CheckBox(this).apply { text = c; setTextColor(0xFFE6EDF3.toInt()); isChecked = c in cfg.chains }
            chainBoxes[c] = cb; chainRow.addView(cb)
        }
        root.addView(HorizontalScrollView(this).apply { addView(chainRow) })

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(tv("Min score ", 14f))
        scoreIn = EditText(this).apply { setText(cfg.alertScore.toInt().toString()); inputType = 2; setTextColor(0xFFFFFFFF.toInt()); minEms = 3 }
        row.addView(scoreIn)
        row.addView(tv("  Poll sec ", 14f))
        pollIn = EditText(this).apply { setText(cfg.pollSeconds.toString()); inputType = 2; setTextColor(0xFFFFFFFF.toInt()); minEms = 3 }
        row.addView(pollIn)
        root.addView(row)
        root.addView(tv("Changes apply the next time you start the scanner.", 11f, 0xFF8B98A5.toInt()))

        val bat = Button(this).apply { text = "Allow background (battery: unrestricted)"; setOnClickListener { batteryPrompt() } }
        root.addView(bat)

        root.addView(tv("Recent signals", 16f).apply { setPadding(0, dp(12), 0, dp(4)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tv("Suggestions only, not financial advice. Meme coins can go to zero.", 11f, 0xFF8B98A5.toInt()))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun onToggle() {
        if (Store.isEnabled(this)) { RadarService.stop(this); return }
        val chains = chainBoxes.filter { it.value.isChecked }.keys.toList().ifEmpty { listOf("solana") }
        Store.saveCfg(this, chains,
            scoreIn.text.toString().toDoubleOrNull()?.coerceIn(30.0, 100.0) ?: 70.0,
            pollIn.text.toString().toIntOrNull()?.coerceIn(20, 600) ?: 45)
        RadarService.start(this)
    }

    private fun batteryPrompt() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        else Toast.makeText(this, "Already unrestricted ✓", Toast.LENGTH_SHORT).show()
    }

    private val tick = object : Runnable {
        override fun run() {
            val on = Store.isEnabled(this@MainActivity)
            toggle.text = if (on) "■ Stop scanning" else "▶ Start 24/7 scanning"
            status.text = Store.status(this@MainActivity)
            list.removeAllViews()
            val sigs = Store.signals(this@MainActivity)
            if (sigs.isEmpty()) list.addView(tv("No signals yet. Strict filters mean quiet stretches are normal.", 13f, 0xFF8B98A5.toInt()))
            val fmt = SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
            for (s in sigs) {
                val card = tv("${s.getString("sym")} (${s.getString("chain")})  score ${s.getDouble("score")}\n" +
                    "${fmt.format(Date(s.getLong("t")))} · mcap ${fmtUsd(s.getDouble("mcap"))} · liq ${fmtUsd(s.getDouble("liq"))} · 1h ${"%+.0f".format(s.getDouble("h1"))}%\n" +
                    s.getString("sec"), 13f).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    setBackgroundColor(0xFF161B22.toInt())
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.getString("url")))) }
                }
                list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            }
            h.postDelayed(this, 3000)
        }
    }
    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
}
