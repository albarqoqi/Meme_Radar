package com.memeradar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (Store.isEnabled(c)) try { RadarService.start(c) } catch (_: Exception) {}
    }
}
