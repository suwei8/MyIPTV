package com.sw.iptv.core;

import android.net.Uri;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.UriUtil;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;

import com.sw.iptv.App;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 磁盘缓存 + 后台全速预拉。
 * <p>
 * 播放读写磁盘缓存(重复内容/回看零流量);同时后台线程把整个点播文件
 * 或VOD切片提前下到磁盘——预拉与播放堆内缓冲解耦,网速在就一直拉,
 * 直到下完。磁盘2GB按最久未用自动淘汰。直播(无ENDLIST)不预拉。
 */
@OptIn(markerClass = UnstableApi.class)
public final class MediaCache {

    private static final long EVICT_LIMIT = 2L << 30;   // 磁盘缓存上限2GB
    private static final int LOG_EVERY = 20;            // HLS每20片记一次进度
    private static final long COOLDOWN_MS = 60_000;     // 预拉403后该URL冷却时长
    private static final long SLOW_CAP_MS = 3_000;      // 节流后切片间隔上限
    private static final long FETCH_MIN_MS = 50;        // <50ms视为缓存命中,不计节流
    private static final int SLEEP_EVERY = 2;           // 缓存命中walk提速:每2片才可能sleep一次

    private static volatile CacheDataSource.Factory factory;
    private static boolean initFailed = false;

    private static volatile boolean stopFlag = false;
    private static volatile CacheWriter activeWriter;
    private static volatile String prefetchingUrl;
    private static Thread prefetchThread;
    // 每URL的预拉403失败次数/冷却截止(时间戳ms);首拉全速,403后自适应降速
    private static final Map<String, Integer> urlFails = new HashMap<>();
    private static final Map<String, Long> coolUntil = new HashMap<>();
    // 已完整下完的URL(重启kick时跳过,避免重复walk)
    private static volatile String completedUrl;

    private MediaCache() {}

    /** 包装数据源工厂: 网络→磁盘缓存→播放。缓存初始化失败时降级为直连。 */
    public static synchronized DataSource.Factory wrap(DataSource.Factory upstream) {
        if (factory == null && !initFailed) {
            try {
                File dir = new File(App.ctx().getCacheDir(), "media_cache");
                SimpleCache cache = new SimpleCache(dir,
                        new LeastRecentlyUsedCacheEvictor(EVICT_LIMIT),
                        new StandaloneDatabaseProvider(App.ctx()));
                factory = new CacheDataSource.Factory()
                        .setCache(cache)
                        .setUpstreamDataSourceFactory(upstream)
                        // 磁盘异常(写满等)不阻断播放,仅跳过缓存
                        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
            } catch (Exception e) {
                initFailed = true;
                LogBuffer.log("prefetch", "磁盘缓存初始化失败,退回直连: " + e);
            }
        }
        return factory != null ? factory : upstream;
    }

    /** 当前正在预拉的URL(无则null) */
    public static String prefetching() {
        return prefetchingUrl;
    }

    /**
     * 开始全速预拉该URL(自动取消上一个)。
     * m3u8→拉全部VOD切片;其它→整文件下到磁盘。
     * 已下完的URL直接跳过;403冷却期内跳过(限流窗口过了由外层kick再触发)。
     */
    public static synchronized void startPrefetch(String url) {
        if (factory == null || url == null || url.isEmpty()) return;
        if (url.equals(completedUrl)) return;   // 已完整下完,不重复walk
        long now = System.currentTimeMillis();
        Long cool = coolUntil.get(url);
        if (cool != null) {
            if (now < cool) {
                LogBuffer.log("prefetch", "403冷却中,跳过预拉(" + ((cool - now + 999) / 1000) + "s): " + url);
                return;
            }
            coolUntil.remove(url);
            LogBuffer.log("prefetch", "冷却结束,恢复预拉: " + url);
        }
        cancelPrefetch();
        stopFlag = false;
        prefetchingUrl = url;
        Thread t = new Thread(() -> runPrefetch(url), "iptv-prefetch");
        t.setPriority(Thread.MIN_PRIORITY);  // 播放拉流优先,预拉用剩余带宽
        prefetchThread = t;
        t.start();
    }

    /** 换台/退出时取消预拉 */
    public static synchronized void cancelPrefetch() {
        stopFlag = true;
        prefetchingUrl = null;
        completedUrl = null;
        CacheWriter w = activeWriter;
        if (w != null) w.cancel();
        Thread t = prefetchThread;
        if (t != null) t.interrupt();
        prefetchThread = null;
    }

    // ---------- 预拉线程 ----------

    private static void runPrefetch(String url) {
        long t0 = System.currentTimeMillis();
        try {
            if (url.toLowerCase(Locale.US).contains(".m3u8")) prefetchHls(url);
            else prefetchFile(url);
            if (!stopFlag) {
                LogBuffer.log("prefetch", "预拉完成(" + (System.currentTimeMillis() - t0)
                        + "ms): " + url);
                urlFails.remove(url);   // 成功则重置节流,下次回到全速
                completedUrl = url;
            }
        } catch (Exception e) {
            if (!stopFlag) {
                logFetchError("预拉失败", e);
                Integer f = urlFails.get(url);
                urlFails.put(url, (f == null ? 0 : f) + 1);
                coolUntil.put(url, System.currentTimeMillis() + COOLDOWN_MS);
                LogBuffer.log("prefetch", "进入冷却" + (COOLDOWN_MS / 1000) + "s,失败"
                        + urlFails.get(url) + "次: " + url);
            }
        } finally {
            if (prefetchingUrl != null && prefetchingUrl.equals(url)) {
                prefetchingUrl = null;
            }
        }
    }

    /** 整文件预拉(CacheWriter自动处理Range/无Range服务器),直到EOF或被取消 */
    private static void prefetchFile(String url) throws Exception {
        CacheDataSource ds = factory.createDataSource();
        CacheWriter w = null;
        try {
            DataSpec spec = new DataSpec.Builder().setUri(Uri.parse(url)).build();
            CacheWriter[] holder = new CacheWriter[1];
            w = new CacheWriter(ds, spec, null,
                    (cached, total, pct) -> {
                        if (stopFlag && holder[0] != null) holder[0].cancel();
                    });
            holder[0] = w;
            activeWriter = w;
            if (stopFlag) return;
            w.cache();
        } finally {
            clearActive(w);
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    /** VOD HLS: 解析播放列表,逐片预拉到磁盘 */
    private static void prefetchHls(String url) throws Exception {
        HlsPlaylistParser parser = new HlsPlaylistParser();
        String mediaUrl = url;
        HlsPlaylist pl = parsePlaylist(parser, url, readText(url));
        if (stopFlag) return;

        if (pl instanceof HlsMultivariantPlaylist) {
            HlsMultivariantPlaylist master = (HlsMultivariantPlaylist) pl;
            if (master.variants.isEmpty()) return;
            mediaUrl = UriUtil.resolve(url, master.variants.get(0).url.toString());
            pl = parsePlaylist(parser, mediaUrl, readText(mediaUrl));
            if (stopFlag) return;
        }
        if (!(pl instanceof HlsMediaPlaylist)) return;

        HlsMediaPlaylist media = (HlsMediaPlaylist) pl;
        // 无ENDLIST=直播/滚动窗口,内容还在生成,不整段预拉
        if (!media.hasEndTag || media.segments.isEmpty()) return;

        LogBuffer.log("prefetch", "hls预拉 " + media.segments.size() + "片: " + mediaUrl);
        Integer f = urlFails.get(url);
        final int fails = f == null ? 0 : f;   // 403降速: 1次→1s/片,2次→2s,3次+→3s封顶
        CacheDataSource ds = factory.createDataSource();
        try {
            int i = 0;
            for (HlsMediaPlaylist.Segment seg : media.segments) {
                if (stopFlag) return;
                long tSeg = System.currentTimeMillis();
                prefetchSegment(ds, mediaUrl, seg);
                boolean fetched = System.currentTimeMillis() - tSeg >= FETCH_MIN_MS;
                i++;
                if (i % LOG_EVERY == 0) {
                    LogBuffer.log("prefetch", "hls切片进度 " + i + "/" + media.segments.size());
                }
                // 仅真实网络拉取才节流(缓存命中walk不拖慢);失败过的URL降低请求速率防源站403
                if (fetched && fails > 0 && !stopFlag && (i % SLEEP_EVERY == 0)) {
                    Thread.sleep(Math.min(1000L * fails, SLOW_CAP_MS));
                }
            }
        } finally {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    private static void prefetchSegment(CacheDataSource ds, String baseUrl,
                                        HlsMediaPlaylist.Segment seg) throws Exception {
        if (seg.fullSegmentEncryptionKeyUri != null && !seg.fullSegmentEncryptionKeyUri.isEmpty()) {
            writeOne(ds, UriUtil.resolve(baseUrl, seg.fullSegmentEncryptionKeyUri), -1, -1);
        }
        HlsMediaPlaylist.Segment init = seg.initializationSegment;
        if (init != null && init.url != null) {
            writeOne(ds, UriUtil.resolve(baseUrl, init.url), init.byteRangeOffset, init.byteRangeLength);
        }
        if (seg.url != null) {
            writeOne(ds, UriUtil.resolve(baseUrl, seg.url), seg.byteRangeOffset, seg.byteRangeLength);
        }
    }

    /** 拉一个资源进磁盘缓存(rangeLen<=0为整段) */
    private static void writeOne(CacheDataSource ds, String uri,
                                 long rangeOffset, long rangeLen) throws Exception {
        if (stopFlag) return;
        DataSpec.Builder b = new DataSpec.Builder().setUri(Uri.parse(uri));
        if (rangeLen > 0) b.setPosition(rangeOffset).setLength(rangeLen);
        CacheWriter w = new CacheWriter(ds, b.build(), null, null);
        activeWriter = w;
        try {
            if (stopFlag) return;
            w.cache();
        } finally {
            clearActive(w);
        }
    }

    private static HlsPlaylist parsePlaylist(HlsPlaylistParser parser, String url, String text)
            throws Exception {
        return parser.parse(Uri.parse(url),
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 经缓存读取文本(播放列表本身也入缓存) */
    private static String readText(String url) throws Exception {
        DataSource ds = factory.createDataSource();
        try {
            ds.open(new DataSpec.Builder().setUri(Uri.parse(url)).build());
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[16 * 1024];
            int n;
            while (!stopFlag && (n = ds.read(buf, 0, buf.length)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { ds.close(); } catch (Exception ignored) {}
        }
    }

    /** 预拉失败日志: 提取HTTP状态码与具体URL(区分限流403/签名过期/网络错误) */
    private static void logFetchError(String what, Exception e) {
        String extra = "";
        for (Throwable c = e; c != null && c.getCause() != c; c = c.getCause()) {
            if (c instanceof HttpDataSource.InvalidResponseCodeException) {
                HttpDataSource.InvalidResponseCodeException ice =
                        (HttpDataSource.InvalidResponseCodeException) c;
                extra = " HTTP " + ice.responseCode + " " + ice.dataSpec.uri;
                break;
            }
        }
        LogBuffer.log("prefetch", what + ": " + e + extra);
    }

    /** 仅当仍是自己持有的writer时清空,避免误清新任务的引用 */
    private static void clearActive(CacheWriter w) {
        if (w != null && activeWriter == w) activeWriter = null;
    }
}
