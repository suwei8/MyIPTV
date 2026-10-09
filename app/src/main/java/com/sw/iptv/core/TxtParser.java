package com.sw.iptv.core;

import com.sw.iptv.model.Category;
import com.sw.iptv.model.Channel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * DIYP 系 txt 直播源解析器(与原 XLIVE 壳 d.java 行为对齐):
 *
 *   分类名,#genre#                  —— 新分类; "分类名_图标" 拆出图标字段
 *   任意名,http://epg地址,#genre#    —— 行内含 http 的分类行,第3段覆盖 EPG 地址
 *   频道名,url                      —— 频道;同名频道追加为同频道多源
 *   url1#url2#url3                 —— # 分隔多源
 *   url$线路名                      —— $ 后缀给源命名(显示为线路名)
 *   频道名_别名                     —— _ 后为显示别名
 *   本地目录 *.txt                  —— 每个文件成为一个分类(文件名 NNN#分类名[_图标].txt)
 */
public class TxtParser {

    public static class Result {
        public final List<Category> categories = new ArrayList<>();
        public final List<Category> favorites = new ArrayList<>(); // index 0 固定"我的收藏"
        public String epgUrl;                                      // txt 内嵌 EPG(若有)
        public int totalChannels;
    }

    /** 解析远程 txt 文本 + 本地目录文件,返回分类列表(下标0="我的收藏")。 */
    public static Result parse(String content, File... localDirs) {
        Result r = new Result();
        Category fav = new Category();
        fav.name = "我的收藏";
        r.categories.add(fav);

        int channelSeq = 1;
        if (content != null) {
            for (String raw : content.replace("\r\n", "\n").split("\n")) {
                String line = raw.trim();
                if (line.isEmpty()) continue;

                if (line.contains(",#genre#")) {
                    // 分类行(可能内嵌 EPG 地址: 名,http://...,#genre#)
                    if (line.contains("http://") || line.contains("https://")) {
                        String[] parts = line.split(",");
                        if (parts.length > 2 && (parts[2].startsWith("http"))) {
                            r.epgUrl = parts[2].trim();
                        }
                    }
                    Category cat = new Category();
                    String cname = line.split(",")[0].trim();
                    if (cname.contains("_")) {
                        cat.name = cname.split("_")[0];
                        cat.icon = cname.split("_", 2)[1];
                    } else {
                        cat.name = cname;
                    }
                    r.categories.add(cat);
                } else if (line.contains(",")) {
                    Category cur = r.categories.get(r.categories.size() - 1);
                    String name = line.substring(0, line.indexOf(",")).trim();
                    String rest = line.substring(line.indexOf(",") + 1);

                    Channel ch = findChannel(cur, name);
                    if (ch == null) {
                        ch = new Channel();
                        ch.id = String.valueOf(channelSeq++);
                        ch.name = name;
                        if (name.contains("_")) {
                            ch.name = name.split("_")[0];
                            ch.alias = name.split("_", 2)[1];
                        }
                        ch.category = cur.name;
                        cur.channels.add(ch);
                        if (Settings.get().isChannelFavored(ch)) {
                            ch.favored = true;
                            cur.favoredChannels.add(ch);
                            fav.channels.add(ch);
                        }
                    }
                    addSources(ch, rest);
                }
            }
        }

        // 本地 *.txt 文件 → 额外分类
        if (localDirs != null) {
            for (File localDir : localDirs) {
                if (localDir == null || !localDir.isDirectory()) continue;
                File[] files = localDir.listFiles(f -> f.isFile() && f.getName().endsWith(".txt"));
                if (files == null) continue;
                List<File> sorted = new ArrayList<>();
                Collections.addAll(sorted, files);
                Collections.sort(sorted, byIndex());
                for (File f : sorted) {
                    try {
                        Category cat = localFileCategory(f);
                        if (cat != null) r.categories.add(cat);
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        int n = 0;
        for (Category c : r.categories) n += c.channels.size();
        r.totalChannels = n;
        return r;
    }

    private static Channel findChannel(Category cat, String name) {
        for (Channel c : cat.channels) if (c.name.equals(name)) return c;
        return null;
    }

    private static void addSources(Channel ch, String rest) {
        for (String seg : rest.split("#")) {
            String url = seg;
            String srcName = "";
            int d = seg.indexOf('$');
            if (d >= 0) {
                srcName = seg.substring(d + 1);
                url = seg.substring(0, d);
            }
            url = url.trim();
            if (url.isEmpty()) continue;
            ch.urls.add(url);
            ch.urlNames.add(srcName);
        }
    }

    /** 文件名规范: "序号#分类名.txt" 或 "分类名.txt",下划线拆图标 */
    private static Category localFileCategory(File f) throws Exception {
        String base = f.getName().replaceFirst("\\.txt$", "");
        String cname = base.contains("#") ? base.split("#", 2)[1] : base;
        Category cat = new Category();
        if (cname.contains("_")) {
            cat.name = cname.split("_")[0];
            cat.icon = cname.split("_", 2)[1];
        } else {
            cat.name = cname;
        }

        BufferedReader br = new BufferedReader(new FileReader(f));
        String line;
        int seq = 0;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || !line.contains(",") || line.contains("#genre#")) continue;
            Channel ch = new Channel();
            String name = line.substring(0, line.indexOf(",")).trim();
            ch.id = String.valueOf(++seq);
            ch.name = name.contains("_") ? name.split("_")[0] : name;
            ch.alias = name.contains("_") ? name.split("_", 2)[1] : "";
            ch.category = cat.name;
            addSources(ch, line.substring(line.indexOf(",") + 1));
            cat.channels.add(ch);
        }
        br.close();
        return cat.channels.isEmpty() ? null : cat;
    }

    private static Comparator<File> byIndex() {
        return (a, b) -> {
            int ia = a.getName().contains("#") ? safeInt(a.getName().split("#")[0]) : Integer.MAX_VALUE;
            int ib = b.getName().contains("#") ? safeInt(b.getName().split("#")[0]) : Integer.MAX_VALUE;
            return ia - ib;
        };
    }

    private static int safeInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return Integer.MAX_VALUE; }
    }
}
