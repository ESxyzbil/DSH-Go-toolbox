package com.dshfix.modaudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启：用户上次是开着的话，自动把按键服务拉起来 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        boolean running = context.getSharedPreferences("ptt", Context.MODE_PRIVATE)
                .getBoolean("running", true);
        if (!running) return;
        try {
            context.startForegroundService(new Intent(context, PttService.class));
        } catch (Throwable ignored) { }
    }
}
