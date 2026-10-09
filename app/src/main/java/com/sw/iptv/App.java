package com.sw.iptv;

import android.app.Application;
import android.content.Context;

public class App extends Application {
    private static App instance;
    public static volatile boolean playlistDirty = false;

    public static App get() {
        return instance;
    }

    public static Context ctx() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }
}
