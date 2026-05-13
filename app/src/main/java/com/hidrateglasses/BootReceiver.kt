package com.hidrateglasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.hidrateglasses.rokid.RokidOverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Only restart the overlay service if the SYSTEM_ALERT_WINDOW permission was
            // previously granted; otherwise we silently skip.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)
            ) {
                val serviceIntent = Intent(context, RokidOverlayService::class.java)
                    .putExtra(RokidOverlayService.EXTRA_FROM_BOOT, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
