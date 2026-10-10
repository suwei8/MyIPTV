package com.sw.iptv.core;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * 内存日志环形缓冲:记录最近的运行事件(播放、换源、报错),
 * 播放失败时可一键复制出来排查。容量有限,只保留最近 MAX_LINES 行。
 */
public class LogBuffer {
    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static boolean crashHooked = false;

    public static synchronized void log(String tag, String msg) {
        lines.addLast(fmt.format(new Date()) + " [" + tag + "] " + msg);
        while (lines.size() > MAX_LINES) lines.removeFirst();
        Log.d("MyIPTV/" + tag, msg);
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    /** 未捕获异常先写入缓冲,再交给原处理器。 */
    public static synchronized void hookCrashes() {
        if (crashHooked) return;
        crashHooked = true;
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log("crash", t.getName() + ": " + e);
            for (StackTraceElement el : e.getStackTrace()) log("crash", "    at " + el);
            if (prev != null) prev.uncaughtException(t, e);
        });
    }
}
