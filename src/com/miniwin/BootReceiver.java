package com.miniwin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Puts the side handle back after the phone restarts or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        if (FloatService.bubbleWanted(context) && Settings.canDrawOverlays(context)) {
            context.startForegroundService(new Intent(context, FloatService.class)
                    .setAction(FloatService.ACTION_BUBBLE_ON));
        }
    }
}
