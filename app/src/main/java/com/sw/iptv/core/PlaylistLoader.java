package com.sw.iptv.core;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import com.sw.iptv.App;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PlaylistLoader {

    public interface Callback {
        void onLoaded(TxtParser.Result result);
        void onError(String msg);
    }

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    /** 异步拉取 CHANNEL_URL 并解析(含本地 /MyIPTV/*.txt)。 */
    public static void load(Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String url = Settings.get().getChannelUrl();
            LogBuffer.log("playlist", "开始拉取: " + url);
            String body = null;
            String err = null;
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                try {
                    Request req = new Request.Builder().url(url)
                            .header("User-Agent", Settings.get().getUserAgent())
                            .build();
                    try (Response resp = client.newCall(req).execute()) {
                        if (resp.isSuccessful()) {
                            ResponseBody rb = resp.body();
                            body = rb != null ? decodeBody(rb.bytes()) : null;
                        } else {
                            err = "HTTP " + resp.code();
                        }
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
            } else if (url != null) {
                // 本地文件路径直接读
                try {
                    File f = new File(url);
                    if (f.exists()) {
                        FileInputStream in = new FileInputStream(f);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                        in.close();
                        body = new String(bos.toByteArray(), Charset.forName("UTF-8"));
                    }
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }

            if (body == null && err == null) err = "源内容为空";
            if (body == null) {
                String e2 = err;
                LogBuffer.log("playlist", "拉取失败: " + e2);
                main.post(() -> cb.onError(e2 == null ? "加载失败" : e2));
                return;
            }

            String b = body;
            LogBuffer.log("playlist", "拉取成功: " + b.length() + " 字节");
            File appDir = App.ctx().getExternalFilesDir("playlists");
            TxtParser.Result result = TxtParser.parse(b, localDir(), appDir);
            if (result.epgUrl != null && !result.epgUrl.isEmpty()) {
                Settings.get().saveEpgUrl(result.epgUrl);
            }
            main.post(() -> cb.onLoaded(result));
        }).start();
    }

    /** UTF-8 优先,失败回退 GBK(国内源常见)。 */
    private static String decodeBody(byte[] bytes) {
        try {
            String s = new String(bytes, Charset.forName("UTF-8"));
            if (s.indexOf('\uFFFD') < 0) return s;
        } catch (Exception ignored) {
        }
        return new String(bytes, Charset.forName("GBK"));
    }

    public static File localDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), "MyIPTV");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }
}
