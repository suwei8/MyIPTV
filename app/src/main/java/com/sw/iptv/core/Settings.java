package com.sw.iptv.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.sw.iptv.App;
import com.sw.iptv.model.Channel;

/**
 * 设置存储层 —— 键名与原 XLIVE 壳保持一致,便于理解/迁移。
 */
public class Settings {
    private static Settings inst;
    private final SharedPreferences sp;
    private final SharedPreferences.Editor ed;

    public static final String DEFAULT_CHANNEL_URL = "https://v.555606.xyz/iptv.txt";
    public static final String DEFAULT_EPG_URL = "http://epg.112114.xyz/";

    private Settings() {
        sp = App.ctx().getSharedPreferences("XLIVE", Context.MODE_PRIVATE);
        ed = sp.edit();
    }

    public static synchronized Settings get() {
        if (inst == null) inst = new Settings();
        return inst;
    }

    // ---------- 源/EPG ----------
    public String getChannelUrl() { return sp.getString("CHANNEL_URL", DEFAULT_CHANNEL_URL); }
    public void saveChannelUrl(String v) { ed.putString("CHANNEL_URL", v).commit(); }

    public String getEpgUrl() { return sp.getString("EPG_URL", DEFAULT_EPG_URL); }
    public void saveEpgUrl(String v) { ed.putString("EPG_URL", v).commit(); }

    public String getUserAgent() { return sp.getString("UserAgent", "Lavf/57.83.100"); }
    public void saveUserAgent(String v) { ed.putString("UserAgent", v).commit(); }

    // ---------- 播放状态 ----------
    public int getCategoryIndex() { return sp.getInt("CATEGORY_INDEX", 1); }
    public void saveCategoryIndex(int v) { ed.putInt("CATEGORY_INDEX", v).commit(); }
    public int getChannelIndex() { return sp.getInt("CHANNEL_INDEX", 0); }
    public void saveChannelIndex(int v) { ed.putInt("CHANNEL_INDEX", v).commit(); }
    public long getSeekPos(String key) { return sp.getLong("SEEK_POST_" + key, 0L); }
    public void saveSeekPos(String key, long v) { ed.putLong("SEEK_POST_" + key, v).commit(); }
    public int getUrlIndex(Channel c) { return sp.getInt("线路选择" + c.category + c.name, 0); }
    public void saveUrlIndex(Channel c, int i) { ed.putInt("线路选择" + c.category + c.name, i).commit(); }

    // ---------- 收藏 ----------
    public boolean isChannelFavored(Channel c) { return sp.getBoolean("FAVOR" + c.category + "/" + c.name, false); }
    public void setChannelFavored(Channel c, boolean v) { ed.putBoolean("FAVOR" + c.category + "/" + c.name, v).commit(); }

    // ---------- 开关 ----------
    public boolean isEpg() { return sp.getBoolean("is_Epg", true); }
    public boolean isShowEpgList() { return sp.getBoolean("列表EPG", true); }
    public boolean isShowTime() { return sp.getBoolean("显示时间", false); }
    public boolean isShowNetSpeed() { return sp.getBoolean("显示网速", false); }
    public boolean isShowSeekBack() { return sp.getBoolean("开启时移", true); }
    public boolean isRememberPlay() { return sp.getBoolean("REMBER_PLAY", false); }
    public boolean isRememberURLIndex() { return sp.getBoolean("记忆多源", true); }
    public boolean isStartAtPowerOn() { return sp.getBoolean("开机自启", false); }
    public boolean isSwitchChannelInAll() { return sp.getBoolean("跨选分类", true); }
    public boolean isReverseSwitchStyle() { return sp.getBoolean("换台反转", false); }
    public boolean isQuickExitStyle() { return sp.getBoolean("快速退出", true); }
    public boolean isShiJiListStyle() { return sp.getBoolean("传统列表", false); }
    public boolean isEyeProtectMode() { return sp.getBoolean("护眼模式", false); }
    public boolean isChannelNumHide() { return sp.getBoolean("HIDE_CHANNEL_NUM", false); }
    public boolean isCategoryHide(String name) { return sp.getBoolean("HIDE_" + name, false); }

    public void toggle(String key, boolean cur) { ed.putBoolean(key, !cur).commit(); }

    public void setEpg(boolean v) { ed.putBoolean("is_Epg", v).commit(); }
    public void setShowEpgList(boolean v) { ed.putBoolean("列表EPG", v).commit(); }
    public void setShowTime(boolean v) { ed.putBoolean("显示时间", v).commit(); }
    public void setShowNetSpeed(boolean v) { ed.putBoolean("显示网速", v).commit(); }
    public void setShowSeekBack(boolean v) { ed.putBoolean("开启时移", v).commit(); }
    public void setRememberPlay(boolean v) { ed.putBoolean("REMBER_PLAY", v).commit(); }
    public void setRememberURLIndex(boolean v) { ed.putBoolean("记忆多源", v).commit(); }
    public void setStartAtPowerOn(boolean v) { ed.putBoolean("开机自启", v).commit(); }
    public void setSwitchChannelInAll(boolean v) { ed.putBoolean("跨选分类", v).commit(); }
    public void setReverseSwitchStyle(boolean v) { ed.putBoolean("换台反转", v).commit(); }
    public void setQuickExitStyle(boolean v) { ed.putBoolean("快速退出", v).commit(); }
    public void setShiJiListStyle(boolean v) { ed.putBoolean("传统列表", v).commit(); }
    public void setEyeProtectMode(boolean v) { ed.putBoolean("护眼模式", v).commit(); }
    public void setChannelNumHide(boolean v) { ed.putBoolean("HIDE_CHANNEL_NUM", v).commit(); }
    public void setCategoryHide(String name, boolean v) { ed.putBoolean("HIDE_" + name, v).commit(); }

    // ---------- 数值 ----------
    public int getMediaCodec() { return sp.getInt("解码方式", 0); }       // 0自动 1硬解优先 2软解优先
    public void saveMediaCodec(int v) { ed.putInt("解码方式", v).commit(); }
    public int getScreenScale() { return sp.getInt("画面比例", 0); }      // 0适应 1填充 2原始 3拉伸16:9 4拉伸4:3
    public void saveScreenScale(int v) { ed.putInt("画面比例", v).commit(); }
    public int getTimeOut() { return sp.getInt("超时换源", 8); }          // 秒,0=不换
    public void saveTimeOut(int v) { ed.putInt("超时换源", v).commit(); }
    public int getChannelListStyle() { return sp.getInt("列表风格", 0); }
    public void saveChannelListStyle(int v) { ed.putInt("列表风格", v).commit(); }
}
