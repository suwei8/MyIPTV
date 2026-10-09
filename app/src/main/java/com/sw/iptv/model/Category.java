package com.sw.iptv.model;

import java.util.ArrayList;
import java.util.List;

public class Category {
    public String name;
    public String icon;                          // 分类_图标 扩展字段
    public final List<Channel> channels = new ArrayList<>();
    public final List<Channel> favoredChannels = new ArrayList<>();
}
