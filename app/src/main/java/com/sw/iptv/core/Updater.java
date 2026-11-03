package com.sw.iptv.core;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.content.pm.PackageInfoCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 应用自更新: 从公开分发仓库 (GitHub Releases) 检查新版本、下载 APK 并调起系统安装器。
 *
 * <p>实现"免卸载热更新"的关键前提:
 * <ul>
 *   <li>每个版本都用同一把 release keystore 签名;</li>
 *   <li>新包 versionCode 严格大于已安装版本。</li>
 * </ul>
 * 满足后系统会直接覆盖安装, 无需先卸载旧版。下载后会比对签名,
 * 若签名不一致(例如误发了 debug 包)会提前给出明确提示, 避免安装时报错。</p>
 */
public class Updater {
    private static final String TAG = "update";
    private static final long CONNECT_TIMEOUT = 15L;
    private static final long READ_TIMEOUT = 180L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile OkHttpClient http;

    private Updater() {
    }

    // ---------- 版本信息 ----------

    public static int currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return (int) PackageInfoCompat.getLongVersionCode(pi);
        } catch (Exception e) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---------- 数据结构 ----------

    /** 远端最新发布信息。 */
    public static class Release {
        public int versionCode;   // 0 表示未知, 此时用 versionName 比较
        public String versionName;
        public String apkUrl;
        public String notes;
        public String signed;     // "release" / "debug" / null
        public long size;

        public boolean isNewerThan(int curCode, String curName) {
            if (versionCode > 0 && curCode > 0) return versionCode > curCode;
            return compareVersionName(versionName, curName) > 0;
        }
    }

    public interface CheckCallback {
        /** rel!=null 有新版本; rel==null 且 error==null 已是最新; error!=null 检查失败。 */
        void onResult(Release rel, String error);
    }

    public interface ProgressCallback {
        void onProgress(long done, long total);
    }

    public interface DownloadCallback {
        /** apk!=null 表示成功(已校验签名); error!=null 表示失败。 */
        void onDone(File apk, String error);
    }

    // ---------- 检查更新 ----------

    public static void check(final Context ctx, final CheckCallback cb) {
        final String url = Settings.get().getUpdateUrl();
        new Thread(() -> {
            Release rel = null;
            String err = null;
            try {
                String body = httpGet(url, "application/vnd.github+json");
                rel = parseRelease(new JSONObject(body));
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
                LogBuffer.log(TAG, "检查更新失败 " + err);
            }
            final Release fRel = rel;
            final String fErr = err;
            MAIN.post(() -> cb.onResult(fRel, fErr));
        }, "update-check").start();
    }

    private static Release parseRelease(JSONObject root) throws Exception {
        Release r = new Release();
        String tag = root.optString("tag_name", "");
        r.versionName = normalizeVersion(tag);
        r.notes = root.optString("body", "");

        JSONArray assets = root.optJSONArray("assets");
        String versionJsonUrl = null;
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "");
                String dl = a.optString("browser_download_url", "");
                if ("version.json".equalsIgnoreCase(name)) {
                    versionJsonUrl = dl;
                } else if (name.toLowerCase().endsWith(".apk") && r.apkUrl == null) {
                    r.apkUrl = dl;
                    r.size = a.optLong("size", 0);
                }
            }
        }
        // 同时存在 debug/release 两个包时, 优先使用非 debug 包
        if (assets != null && r.apkUrl != null && r.apkUrl.toLowerCase().contains("-debug")) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "").toLowerCase();
                if (name.endsWith(".apk") && !name.contains("-debug")) {
                    r.apkUrl = a.optString("browser_download_url", "");
                    r.size = a.optLong("size", 0);
                    break;
                }
            }
        }
        if (versionJsonUrl != null) {
            try {
                JSONObject vj = new JSONObject(httpGet(versionJsonUrl, "application/json"));
                r.versionCode = vj.optInt("versionCode", 0);
                String vn = vj.optString("versionName", "");
                if (!vn.isEmpty()) r.versionName = vn;
                String signed = vj.optString("signed", "");
                if (!signed.isEmpty()) r.signed = signed;
                String apk = vj.optString("apk", "");
                if (!apk.isEmpty()) r.apkUrl = apk;
            } catch (Exception ignore) {
                // version.json 缺失/损坏时退化为按 tag 名比较
            }
        }
        if (r.apkUrl == null || r.apkUrl.isEmpty()) throw new Exception("发布中没有找到 APK 文件");
        return r;
    }

    // ---------- 下载 ----------

    public static void download(final Context ctx, final Release rel,
                                final ProgressCallback prog, final DownloadCallback cb) {
        new Thread(() -> {
            File dir = updateDir(ctx);
            File tmp = new File(dir, "MyIPTV-update.apk.tmp");
            File out = new File(dir, "MyIPTV-update.apk");
            String err = null;
            try {
                Request req = new Request.Builder()
                        .url(rel.apkUrl)
                        .header("Accept", "application/octet-stream")
                        .header("User-Agent", "MyIPTV-Updater")
                        .build();
                Response resp = client().newCall(req).execute();
                if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());
                ResponseBody body = resp.body();
                if (body == null) throw new Exception("空响应");
                long total = body.contentLength();
                if (total <= 0) total = rel.size;
                InputStream in = body.byteStream();
                FileOutputStream fos = new FileOutputStream(tmp);
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (prog != null) prog.onProgress(done, total);
                }
                fos.flush();
                fos.close();
                in.close();
                resp.close();
                if (out.exists() && !out.delete()) {
                    // 旧文件删不掉时换个名字, 保证本次一定能落盘
                    out = new File(dir, "MyIPTV-update-" + System.currentTimeMillis() + ".apk");
                }
                if (!tmp.renameTo(out)) throw new Exception("无法保存安装包");
                err = verifySignature(ctx, out);
                if (err == null) LogBuffer.log(TAG, "更新包已下载: " + out.getName());
            } catch (Exception e) {
                err = e.getClass().getSimpleName() + ": " + e.getMessage();
                LogBuffer.log(TAG, "下载更新失败 " + err);
                tmp.delete();
            }
            final File fOut = out;
            final String fErr = err;
            MAIN.post(() -> cb.onDone(fErr == null ? fOut : null, fErr));
        }, "update-download").start();
    }

    // ---------- 签名校验(防签名不一致导致安装失败) ----------

    private static String verifySignature(Context ctx, File apk) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Set<String> cur = certHashes(pm, ctx.getPackageName(), false);
            Set<String> neu = certHashes(pm, apk.getAbsolutePath(), true);
            if (cur == null || cur.isEmpty() || neu == null || neu.isEmpty()) {
                return null; // 读不到签名就不拦截, 交给系统安装器判断
            }
            for (String h : neu) if (cur.contains(h)) return null; // 有交集即可
            return "安装包签名与当前版本不一致(可能是 debug 包), 需卸载后重装";
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Set<String> certHashes(PackageManager pm, String pkgOrPath, boolean isApk)
            throws Exception {
        PackageInfo pi;
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            int flags = PackageManager.GET_SIGNING_CERTIFICATES;
            pi = isApk ? pm.getPackageArchiveInfo(pkgOrPath, flags) : pm.getPackageInfo(pkgOrPath, flags);
            if (pi != null && pi.signingInfo != null) {
                sigs = pi.signingInfo.hasMultipleSigners()
                        ? pi.signingInfo.getApkContentsSigners()
                        : pi.signingInfo.getSigningCertificateHistory();
            }
        } else {
            int flags = PackageManager.GET_SIGNATURES;
            pi = isApk ? pm.getPackageArchiveInfo(pkgOrPath, flags) : pm.getPackageInfo(pkgOrPath, flags);
            if (pi != null) sigs = pi.signatures;
        }
        if (sigs == null || sigs.length == 0) return null;
        Set<String> out = new HashSet<>();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (Signature s : sigs) {
            md.reset();
            byte[] d = md.digest(s.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            out.add(sb.toString());
        }
        return out;
    }

    // ---------- 安装 ----------

    /** API 26+ 需用户授权"安装未知应用"。返回 true 表示可直接安装。 */
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ctx.getPackageManager().canRequestPackageInstalls();
        }
        return true;
    }

    /** 打开系统的"安装未知应用"授权页。 */
    public static void openInstallSettings(Activity act) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        try {
            act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + act.getPackageName())));
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
            } catch (Exception ignore) {
                Toast.makeText(act, "无法打开安装授权页", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 用系统安装器覆盖安装(无需卸载)。 */
    public static void install(Activity act, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            LogBuffer.log(TAG, "已调起系统安装器: " + apk.getName());
        } catch (Exception e) {
            Toast.makeText(act, "无法调起安装器: " + e.getMessage(), Toast.LENGTH_LONG).show();
            LogBuffer.log(TAG, "调起安装器失败 " + e);
        }
    }

    /** 更新包下载目录(外部私有目录, 无需存储权限)。 */
    public static File updateDir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File dir = new File(base, "update");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ---------- 工具 ----------

    private static OkHttpClient client() {
        if (http == null) {
            synchronized (Updater.class) {
                if (http == null) {
                    http = new OkHttpClient.Builder()
                            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
                            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return http;
    }

    private static String httpGet(String url, String accept) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("Accept", accept)
                .header("User-Agent", "MyIPTV-Updater")
                .build();
        try (Response resp = client().newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new Exception("HTTP " + resp.code());
            ResponseBody b = resp.body();
            if (b == null) throw new Exception("空响应");
            return b.string();
        }
    }

    private static String normalizeVersion(String tag) {
        if (tag == null || tag.isEmpty()) return "0";
        return (tag.charAt(0) == 'v' || tag.charAt(0) == 'V') ? tag.substring(1) : tag;
    }

    /** 逐段比较版本号, 返回 >0 表示 a 比 b 新。 */
    public static int compareVersionName(String a, String b) {
        String[] x = normalizeVersion(a).split("[^0-9]+");
        String[] y = normalizeVersion(b).split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = (i < x.length && !x[i].isEmpty()) ? parseIntSafe(x[i]) : 0;
            int yi = (i < y.length && !y[i].isEmpty()) ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi - yi;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
