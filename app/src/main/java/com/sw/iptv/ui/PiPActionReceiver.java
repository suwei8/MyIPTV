package com.sw.iptv.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 小窗(play/pause)系统按钮的回调: 经静态引用切回播放器暂停/继续 */
public class PiPActionReceiver extends BroadcastReceiver {
    public static final String ACTION_TOGGLE = "com.sw.iptv.PIP_TOGGLE";

    @Override
    public void onReceive(Context context, Intent intent) {
        MainActivity a = MainActivity.current;
        if (a != null) a.runOnUiThread(a::togglePause);
    }
}
