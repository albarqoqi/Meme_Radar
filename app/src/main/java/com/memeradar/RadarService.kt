package com.memeradar

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.*

class RadarService : Service() {
    @Volatile private var running = false
    private var worker: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("scan", "Scanner status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("signal", "Coin signals", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 250, 120, 250)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopScan(); return START_NOT_STICKY }
        startForegroundCompat(statusNote("Starting…"))
        if (!running) {
            running = true
            Store.setEnabled(this, true)
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "memeradar:scan").apply { acquire() }
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun loop() {
        val cfg = Store.cfg(this)
        val radar = Radar(cfg)
        var fails = 0
        while (running) {
            val t0 = System.currentTimeMillis()
            try {
                val sigs = radar.cycle()
                fails = 0
                sigs.forEach { Store.addSignal(this, it); notifySignal(it) }
                val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val top = radar.top?.let { " · top ${it.symbol} ${it.score}" } ?: ""
                val s = "Scanning ${cfg.chains.joinToString("/")} · $time · universe ${radar.universeSize} · " +
                        "qualified ${radar.qualifiedCount}$top"
                Store.setStatus(this, s)
                getSystemService(NotificationManager::class.java).notify(1, statusNote(s))
            } catch (e: InterruptedException) { return }
            catch (e: Exception) { fails++; Store.setStatus(this, "Error: ${e.message} (retry)") }
            val backoff = if (fails > 0) minOf(300L, 15L * fails) else 0L
            val sleep = maxOf(1000L, cfg.pollSeconds * 1000L - (System.currentTimeMillis() - t0)) + backoff * 1000
            try { Thread.sleep(sleep) } catch (e: InterruptedException) { return }
        }
    }

    private fun notifySignal(c: Cand) {
        val open = PendingIntent.getActivity(this, c.key.hashCode(),
            Intent(Intent.ACTION_VIEW, Uri.parse(c.url)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "signal")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("🚨 ${c.symbol} (${c.chain}) score ${c.score}")
            .setContentText("mcap ${fmtUsd(c.mcap)} · liq ${fmtUsd(c.liq)} · age ${c.ageMin.toInt()}m · 1h ${"%+.0f".format(c.chH1)}%")
            .setStyle(Notification.BigTextStyle().bigText(
                "mcap ${fmtUsd(c.mcap)} · liq ${fmtUsd(c.liq)} · age ${c.ageMin.toInt()}m\n" +
                "5m ${"%+.0f".format(c.chM5)}% · 1h ${"%+.0f".format(c.chH1)}% · buys ${"%.0f".format(c.buyRatioH1 * 100)}%\n" +
                "Safety: ${c.secNotes.joinToString("; ")}" +
                (if (c.flags.isNotEmpty()) "\nFlags: ${c.flags.joinToString(",")}" else "") +
                "\nSuggestion only — not financial advice. Tap to open chart."))
            .setContentIntent(open).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(1000 + (c.key.hashCode() and 0xFFFF), n)
    }

    private fun statusNote(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RadarService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "scan").setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Meme Radar running 24/7").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setOngoing(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun stopScan() {
        running = false; worker?.interrupt()
        Store.setEnabled(this, false); Store.setStatus(this, "Stopped")
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        running = false; worker?.interrupt()
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) { c.startForegroundService(Intent(c, RadarService::class.java)) }
        fun stop(c: Context) { c.startService(Intent(c, RadarService::class.java).setAction("STOP")) }
    }
}
