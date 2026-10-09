package com.sw.iptv.epg;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * DIYP EPG 协议: GET {base}?ch={频道名}&date={yyyy-MM-dd}
 * 返回 {"channel_name":..., "date":..., "epg_data":[{"start":"08:00","end":"09:00","title":"..."}]}
 */
public class EpgClient {

    public static class EpgItem {
        public String start;
        public String end;
        public String title;
        public long startMs;
        public long endMs;
    }

    public interface Callback {
        void onResult(List<EpgItem> items);
    }

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final ConcurrentHashMap<String, List<EpgItem>> cache = new ConcurrentHashMap<>();

    public static void query(String base, String channel, Callback cb) {
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String key = base + "|" + channel + "|" + date;
        List<EpgItem> hit = cache.get(key);
        if (hit != null) {
            cb.onResult(hit);
            return;
        }
        new Thread(() -> {
            List<EpgItem> items = new ArrayList<>();
            try {
                String sep = base.contains("?") ? "&" : "?";
                String url = base + sep + "ch=" + java.net.URLEncoder.encode(channel, "UTF-8") + "&date=" + date;
                Request req = new Request.Builder().url(url).build();
                try (Response resp = client.newCall(req).execute()) {
                    ResponseBody rb = resp.body();
                    if (resp.isSuccessful() && rb != null) {
                        JSONObject obj = new JSONObject(rb.string());
                        JSONArray arr = obj.optJSONArray("epg_data");
                        if (arr != null) {
                            Calendar cal = Calendar.getInstance();
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject it = arr.optJSONObject(i);
                                if (it == null) continue;
                                EpgItem e = new EpgItem();
                                e.start = it.optString("start");
                                e.end = it.optString("end");
                                e.title = it.optString("title");
                                e.startMs = toMs(cal, e.start);
                                e.endMs = toMs(cal, e.end);
                                items.add(e);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            cache.put(key, items);
            cb.onResult(items);
        }).start();
    }

    /** 当前时刻节目: [0]=正在播放, [1]=下一节目, 查不到返回 null。 */
    public static EpgItem[] nowNext(List<EpgItem> items) {
        if (items == null || items.isEmpty()) return null;
        long now = System.currentTimeMillis();
        for (int i = 0; i < items.size(); i++) {
            EpgItem e = items.get(i);
            long end = e.endMs > 0 ? e.endMs : (i + 1 < items.size() ? items.get(i + 1).startMs : Long.MAX_VALUE);
            if (now >= e.startMs && now < end) {
                return new EpgItem[]{e, i + 1 < items.size() ? items.get(i + 1) : null};
            }
        }
        return new EpgItem[]{items.get(items.size() - 1), null};
    }

    private static long toMs(Calendar day, String hhmm) {
        try {
            String[] p = hhmm.split(":");
            Calendar c = (Calendar) day.clone();
            c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0]));
            c.set(Calendar.MINUTE, Integer.parseInt(p[1]));
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}
