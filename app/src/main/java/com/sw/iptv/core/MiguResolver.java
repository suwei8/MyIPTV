package com.sw.iptv.core;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 咪咕视频官方接口直播源解析(develop202 算法, 移植自 migu_lnss.php)。
 * iptv.txt 中写 migu://<pid> , 播放时现签现解析, 流地址与请求 IP 绑定,
 * 因此必须在播放设备本机解析, 不能走服务器/代理。
 */
public final class MiguResolver {

    public static final String SCHEME = "migu://";

    private static final String APP_VERSION = "2600034600";
    private static final String MIGU_UA = "MiguVideo/6.2.30";
    private static final Pattern PUDATA = Pattern.compile("&puData=([^&]+)");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build();

    public static boolean isMigu(String url) {
        return url != null && url.startsWith(SCHEME);
    }

    public static String pidOf(String url) {
        return isMigu(url) ? url.substring(SCHEME.length()) : null;
    }

    /** 解析 migu://pid 到最终可播 m3u8 地址; 失败返回 null。须在子线程调用。 */
    public static String resolve(String miguUrl) {
        String pid = pidOf(miguUrl);
        if (pid == null) return null;
        try {
            return miguResolve(pid);
        } catch (Exception e) {
            LogBuffer.log("migu", "resolve fail pid=" + pid + " : " + e);
            return null;
        }
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
        }
        return sb.toString();
    }

    /** playurl 签名请求: 时间戳+contId+版本前缀 md5, 再叠盐值后缀 md5 */
    private static Request apiRequest(String pid) throws Exception {
        String t = String.valueOf(System.currentTimeMillis());
        String v = APP_VERSION;
        String cid = md5(String.valueOf(System.nanoTime()));
        String salt = String.format(Locale.US, "%06d", new Random().nextInt(1000000)) + "25";
        String sign = md5(md5(t + pid + v.substring(0, 8))
                + "2cac4f2c6c3346a5b34e085725ef7e33migu" + salt.substring(0, 4));
        String url = "https://play.miguvideo.com/playurl/v1/play/playurl?sign=" + sign
                + "&rateType=3&contId=" + pid + "&timestamp=" + t + "&salt=" + salt
                + "&flvEnable=true&super4k=true&h265N=true&4kvivid=true&2Kvivid=true&vivid=2";
        Request.Builder b = new Request.Builder().url(url)
                .header("AppVersion", v)
                .header("TerminalId", "android")
                .header("X-UP-CLIENT-CHANNEL-ID", v + "-99000-201600010010028")
                .header("ClientId", cid);
        if (!"641886683".equals(pid) && !"641886773".equals(pid)) {
            b.header("appCode", "miguvideo_default_android");
        }
        return b.build();
    }

    /** puData → ddCalcu(逐字符交叉拼接, 按日/按频道插字) */
    private static String ddCalcu(String puData, String pid) {
        String keys = "cdabyzwxkl";
        StringBuilder dd = new StringBuilder();
        int n = puData.length();
        for (int i = 0; i < n / 2; i++) {
            dd.append(puData.charAt(n - i - 1)).append(puData.charAt(i));
            if (i == 1) dd.append('v');
            if (i == 2) {
                int idx = Integer.parseInt(
                        new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()).substring(2, 3));
                dd.append(keys.charAt(idx));
            }
            if (i == 3) dd.append(keys.charAt(Character.getNumericValue(pid.charAt(6))));
            if (i == 4) dd.append('a');
        }
        return dd.toString();
    }

    /** http://*.miguvideo.com:8080/x -> https://*.miguvideo.com/x (443同内容,免劫持); 非咪咕域名返回null */
    private static String toHttps(String url) {
        if (url == null || !url.startsWith("http://") || !url.contains("miguvideo.com")) {
            return null;
        }
        return "https://" + url.substring(7).replace(":8080/", "/");
    }

    /** 跟随 302 链至终链; preferHttps=true 时每跳优先改写为 https。异常/无终链返回 null */
    private static String followChain(String url, boolean preferHttps) {
        for (int i = 0; i < 6; i++) {
            try {
                Request req = new Request.Builder().url(url)
                        .header("User-Agent", MIGU_UA).build();
                try (Response r = CLIENT.newCall(req).execute()) {
                    if (r.code() != 302) return url;
                    String loc = r.header("Location");
                    if (loc == null || loc.isEmpty() || loc.startsWith("http://bofang")) return url;
                    if (preferHttps) {
                        String h = toHttps(loc);
                        if (h != null) loc = h;
                    }
                    url = loc;
                }
            } catch (Exception e) {
                return null;
            }
        }
        return url;
    }

    /** playurl 签名→puData/ddCalcu→手动跟 302(UA MiguVideo)→终链 */
    private static String miguResolve(String pid) throws Exception {
        try (Response res = CLIENT.newCall(apiRequest(pid)).execute()) {
            if (res.body() == null) return null;
            JSONObject j = new JSONObject(res.body().string());
            JSONObject ui = j.optJSONObject("body");
            if (ui == null) return null;
            ui = ui.optJSONObject("urlInfo");
            if (ui == null) return null;
            String url = ui.optString("url", null);
            if (url == null || url.isEmpty()) return null;
            Matcher m = PUDATA.matcher(url);
            if (!m.find()) return null;
            url += "&ddCalcu=" + ddCalcu(m.group(1), pid) + "&sv=10004&ct=android";

            // 运营商 HTTP 劫持防护: 先走全 https 链(443同内容,明文链路注入HTML即劫持);
            // 任一环失败再回落原 http 链, 兼顾不支持https的边缘节点
            String r = followChain(toHttps(url) != null ? toHttps(url) : url, true);
            if (r != null) return r;
            return followChain(url, false);
        }
    }
}
