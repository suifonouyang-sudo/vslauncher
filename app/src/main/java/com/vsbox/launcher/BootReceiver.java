package com.vsbox.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Prefs.init(context);
            Logger.init(context);
            if (Prefs.getBool(Prefs.K_BALL, false)) {
                Intent it = new Intent(context, FloatingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(it);
                else context.startService(it);
                Logger.log("开机自动启动悬浮球");
            }
        } catch (Throwable ignored) {
        }
    }
}
