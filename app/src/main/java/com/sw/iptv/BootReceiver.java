package com.sw.iptv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.sw.iptv.core.Settings;
import com.sw.iptv.ui.MainActivity;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!Settings.get().isStartAtPowerOn()) return;
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
