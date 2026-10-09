package com.sw.iptv.model;

import java.util.ArrayList;
import java.util.List;

public class Channel {
    public String id;          // 频道号
    public String name;        // 频道名
    public String alias;       // 别名(频道名_别名)
    public String category;    // 所属分类名
    public boolean favored;    // 收藏
    public final List<String> urls = new ArrayList<>();      // 多源地址
    public final List<String> urlNames = new ArrayList<>();  // 每条源的线路名(url$线路名)

    public String displayName() {
        return (alias != null && !alias.isEmpty()) ? alias : name;
    }

    public String key() {
        return category + "/" + name;
    }
}
