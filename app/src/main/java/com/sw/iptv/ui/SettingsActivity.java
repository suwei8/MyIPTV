package com.sw.iptv.ui;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.sw.iptv.App;
import com.sw.iptv.R;
import com.sw.iptv.core.PlaylistLoader;
import com.sw.iptv.core.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SettingsActivity extends AppCompatActivity {

    private static class Option {
        String name;
        Supplier<String> value;
        Runnable action;

        Option(String n, Supplier<String> v, Runnable a) { name = n; value = v; action = a; }
    }

    private final List<Option> options = new ArrayList<>();
    private TextAdapter<Option> adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        RecyclerView list = findViewById(R.id.settings_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TextAdapter<>(o -> o.name + (o.value == null ? "" : "    " + o.value.get()));
        adapter.onClick = (o, pos) -> { if (o.action != null) o.action.run(); adapter.notifyItemChanged(pos); };
        list.setAdapter(adapter);
        build();
        adapter.setItems(options);
    }

    private void build() {
        Settings s = Settings.get();
        options.clear();

        options.add(new Option("直播源地址", s::getChannelUrl, () ->
                input("直播源地址", s.getChannelUrl(), v -> {
                    s.saveChannelUrl(v.trim());
                    App.playlistDirty = true;
                    toast("已保存,返回后自动重载");
                })));

        options.add(new Option("EPG地址", s::getEpgUrl, () ->
                input("EPG地址", s.getEpgUrl(), v -> s.saveEpgUrl(v.trim()))));

        options.add(new Option("UserAgent", s::getUserAgent, () ->
                input("UserAgent", s.getUserAgent(), v -> s.saveUserAgent(v.trim()))));

        String[] codecs = {"自动", "硬解优先", "软解优先"};
        options.add(new Option("解码方式", () -> codecs[s.getMediaCodec()], () ->
                s.saveMediaCodec((s.getMediaCodec() + 1) % codecs.length)));

        String[] scales = {"适应屏幕", "拉伸充满", "裁剪充满", "固定16:9", "固定4:3"};
        options.add(new Option("画面比例", () -> scales[s.getScreenScale()], () ->
                s.saveScreenScale((s.getScreenScale() + 1) % scales.length)));

        int[] tos = {0, 5, 8, 10, 15, 20};
        String[] toNames = {"关闭", "5秒", "8秒", "10秒", "15秒", "20秒"};
        options.add(new Option("超时换源", () -> toNames[indexOf(tos, s.getTimeOut())], () -> {
            int i = indexOf(tos, s.getTimeOut());
            s.saveTimeOut(tos[(i + 1) % tos.length]);
        }));

        options.add(toggle("EPG显示", s::isEpg, s::setEpg));
        options.add(toggle("列表EPG", s::isShowEpgList, s::setShowEpgList));
        options.add(toggle("显示时间", s::isShowTime, s::setShowTime));
        options.add(toggle("显示网速", s::isShowNetSpeed, s::setShowNetSpeed));
        options.add(toggle("开启时移", s::isShowSeekBack, s::setShowSeekBack));
        options.add(toggle("记忆播放", s::isRememberPlay, s::setRememberPlay));
        options.add(toggle("记忆多源", s::isRememberURLIndex, s::setRememberURLIndex));
        options.add(toggle("开机自启", s::isStartAtPowerOn, s::setStartAtPowerOn));
        options.add(toggle("跨分类换台", s::isSwitchChannelInAll, s::setSwitchChannelInAll));
        options.add(toggle("换台反转", s::isReverseSwitchStyle, s::setReverseSwitchStyle));
        options.add(toggle("快速退出", s::isQuickExitStyle, s::setQuickExitStyle));
        options.add(toggle("传统列表", s::isShiJiListStyle, s::setShiJiListStyle));
        options.add(toggle("护眼模式", s::isEyeProtectMode, s::setEyeProtectMode));
        options.add(toggle("隐藏频道号", s::isChannelNumHide, s::setChannelNumHide));

        options.add(new Option("重新加载播放源", null, () -> {
            App.playlistDirty = true;
            toast("已标记,返回主界面后重载");
        }));

        options.add(new Option("恢复默认源", null, () -> {
            s.saveChannelUrl(Settings.DEFAULT_CHANNEL_URL);
            App.playlistDirty = true;
            toast("已恢复默认源");
        }));
    }

    private Option toggle(String name, Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
        return new Option(name, () -> get.get() ? "开" : "关", () -> set.accept(!get.get()));
    }

    private int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    private void input(String title, String cur, java.util.function.Consumer<String> save) {
        EditText et = new EditText(this);
        et.setText(cur);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout fl = new FrameLayout(this);
        fl.setPadding(pad, pad / 2, pad, 0);
        fl.addView(et);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(fl)
                .setPositiveButton("保存", (d, w) -> save.accept(et.getText().toString()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }
}
