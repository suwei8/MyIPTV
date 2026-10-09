package com.sw.iptv.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.sw.iptv.App;
import com.sw.iptv.R;
import com.sw.iptv.core.PlaylistLoader;
import com.sw.iptv.core.Settings;
import com.sw.iptv.core.TxtParser;
import com.sw.iptv.epg.EpgClient;
import com.sw.iptv.model.Category;
import com.sw.iptv.model.Channel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends AppCompatActivity {

    private PlayerView playerView;
    private View eyeOverlay, loading, infoBar, progressRow, channelPanel;
    private TextView inputNum, infoNum, infoName, infoNow, infoNext, infoTime, infoSpeed, infoLine, loadingText;
    private TextView progCur, progDur;
    private SeekBar progBar;
    private RecyclerView categoryList, channelList;

    private ExoPlayer player;
    private DefaultBandwidthMeter bandwidthMeter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Category> categories = new ArrayList<>();
    private final List<Channel> flatChannels = new ArrayList<>();

    private TextAdapter<Category> catAdapter;
    private ChannelAdapter chAdapter;

    private Category curCategory;
    private Channel curChannel;
    private int curUrlIndex = 0;
    private String numBuf = "";
    private long bufferingSince = 0;
    private boolean panelChannelsFocused = false;
    private int flatIdx = -1;
    private final List<Channel> curList = new ArrayList<>();

    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.US);

    // ---------- lifecycle ----------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        initLists();
        initPlayer();
        loadPlaylist();
        eyeOverlay.setVisibility(Settings.get().isEyeProtectMode() ? View.VISIBLE : View.GONE);
        rootTick();
    }

    private void bindViews() {
        playerView = findViewById(R.id.player_view);
        eyeOverlay = findViewById(R.id.eye_overlay);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loading_text);
        infoBar = findViewById(R.id.info_bar);
        infoNum = findViewById(R.id.info_num);
        infoName = findViewById(R.id.info_name);
        infoNow = findViewById(R.id.info_now);
        infoNext = findViewById(R.id.info_next);
        infoTime = findViewById(R.id.info_time);
        infoSpeed = findViewById(R.id.info_speed);
        infoLine = findViewById(R.id.info_line);
        inputNum = findViewById(R.id.input_num);
        progressRow = findViewById(R.id.progress_row);
        progCur = findViewById(R.id.prog_cur);
        progDur = findViewById(R.id.prog_dur);
        progBar = findViewById(R.id.prog_bar);
        progBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser) progCur.setText(fmtTime(p * 1000L));
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {
                handler.removeCallbacks(hideInfo);
            }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (player != null) player.seekTo(sb.getProgress() * 1000L);
                scheduleInfoHide();
            }
        });
        channelPanel = findViewById(R.id.channel_panel);
        categoryList = findViewById(R.id.category_list);
        channelList = findViewById(R.id.channel_list);

        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(android.view.MotionEvent e) {
                if (channelPanel.getVisibility() == View.VISIBLE) hidePanel();
                else showPanel();
                return true;
            }

            @Override
            public boolean onDoubleTap(android.view.MotionEvent e) {
                if (player != null) player.setPlayWhenReady(!player.getPlayWhenReady());
                return true;
            }

            @Override
            public void onLongPress(android.view.MotionEvent e) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.touch_layer).setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
    }

    private void initLists() {
        catAdapter = new TextAdapter<>(c -> c.name);
        chAdapter = new ChannelAdapter();

        catAdapter.onClick = (cat, pos) -> selectCategory(pos);
        catAdapter.onFocus = (cat, pos) -> selectCategory(pos);
        chAdapter.onClick = (c, pos) -> {
            playChannel(c, curUrlIndexOf(c));
            hidePanel();
        };
        chAdapter.onLongClick = (c, pos) -> toggleFavorite(c);

        categoryList.setLayoutManager(new LinearLayoutManager(this));
        channelList.setLayoutManager(new LinearLayoutManager(this));
        categoryList.setAdapter(catAdapter);
        channelList.setAdapter(chAdapter);
    }

    private void initPlayer() {
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        OkHttpDataSource.Factory dsFactory = new OkHttpDataSource.Factory(http)
                .setUserAgent(Settings.get().getUserAgent());

        DefaultRenderersFactory rf = new DefaultRenderersFactory(this);
        rf.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        rf.setMediaCodecSelector(new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder,
                                                        boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                int mode = Settings.get().getMediaCodec();
                if (mode == 0) return all;
                List<MediaCodecInfo> prefer = new ArrayList<>();
                List<MediaCodecInfo> rest = new ArrayList<>();
                for (MediaCodecInfo i : all) {
                    boolean hw = i.hardwareAccelerated;
                    if (mode == 1 ? hw : !hw) prefer.add(i); else rest.add(i);
                }
                prefer.addAll(rest);
                return prefer;
            }
        });

        bandwidthMeter = new DefaultBandwidthMeter.Builder(this).build();
        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))
                .setBandwidthMeter(bandwidthMeter)
                .build();
        playerView.setPlayer(player);
        playerView.setResizeMode(mapScale(Settings.get().getScreenScale()));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    bufferingSince = System.currentTimeMillis();
                    showLoading("缓冲中…");
                } else if (state == Player.STATE_READY) {
                    bufferingSince = 0;
                    loading.setVisibility(View.GONE);
                    updateInfoBar();
                    scheduleInfoHide();
                } else if (state == Player.STATE_ENDED) {
                    if (player.isCurrentMediaItemLive()) {
                        player.prepare();
                    } else if (curCategory != null && curChannel != null) {
                        // 点播播完自动下一集
                        List<Channel> l = curCategory.channels;
                        int i = l.indexOf(curChannel);
                        if (i >= 0 && i < l.size() - 1) {
                            Channel next = l.get(i + 1);
                            toast("下一集: " + next.displayName());
                            playChannel(next, curUrlIndexOf(next));
                        } else {
                            showLoading("播放完毕");
                        }
                    } else {
                        showLoading("播放完毕");
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                if (curChannel != null && curChannel.urls.size() <= 1) {
                    showLoading("播放失败,20秒后自动重试");
                    handler.postDelayed(singleRetry, 20000);
                    return;
                }
                tryNextSource();
            }
        });

        startWatchdog();
    }

    // ---------- playlist ----------

    private void loadPlaylist() {
        showLoading("正在加载直播源…");
        PlaylistLoader.load(new PlaylistLoader.Callback() {
            @Override
            public void onLoaded(TxtParser.Result result) {
                categories.clear();
                for (Category c : result.categories) {
                    if (Settings.get().isCategoryHide(c.name)) continue;
                    categories.add(c);
                }
                rebuildFlat();
                catAdapter.setItems(categories);

                if (result.totalChannels == 0) {
                    showLoading("源为空,请在设置中检查源地址");
                    return;
                }
                loading.setVisibility(View.GONE);
                restoreLastChannel();
            }

            @Override
            public void onError(String msg) {
                showLoading("源加载失败: " + msg + "\n按菜单键打开设置");
            }
        });
    }

    private void rebuildFlat() {
        flatChannels.clear();
        for (Category c : categories) flatChannels.addAll(c.channels);
    }

    private void restoreLastChannel() {
        if (Settings.get().isRememberPlay()) {
            int ci = Settings.get().getCategoryIndex();
            int chi = Settings.get().getChannelIndex();
            if (ci >= 0 && ci < categories.size()) {
                Category cat = categories.get(ci);
                if (chi >= 0 && chi < cat.channels.size()) {
                    playChannel(cat.channels.get(chi), -1);
                    return;
                }
            }
        }
        if (!flatChannels.isEmpty()) playChannel(flatChannels.get(0), -1);
    }

    // ---------- playback ----------

    private int curUrlIndexOf(Channel c) {
        return Settings.get().isRememberURLIndex()
                ? Math.min(Settings.get().getUrlIndex(c), Math.max(0, c.urls.size() - 1))
                : 0;
    }

    private final Runnable singleRetry = () -> {
        if (curChannel != null && (player == null || !player.isPlaying()))
            playChannel(curChannel, curUrlIndex);
    };

    private void playChannel(Channel c, int urlIndex) {
        if (c == null || c.urls.isEmpty()) {
            toast("该频道无可用源");
            return;
        }
        handler.removeCallbacks(singleRetry);
        curChannel = c;
        for (Category cat : categories) if (cat.channels.contains(c)) { curCategory = cat; break; }
        curUrlIndex = Math.max(0, Math.min(urlIndex, c.urls.size() - 1));
        flatIdx = flatChannels.indexOf(c);

        String url = c.urls.get(curUrlIndex);
        if (Settings.get().isRememberURLIndex()) Settings.get().saveUrlIndex(c, curUrlIndex);

        long seekTo = 0;
        if (Settings.get().isRememberPlay()) seekTo = Settings.get().getSeekPos(c.key());

        MediaItem item = new MediaItem.Builder().setUri(url).build();
        player.setMediaItem(item);
        if (seekTo > 0) player.seekTo(seekTo);
        player.prepare();
        player.setPlayWhenReady(true);

        saveIndexes();
        showInfoBar();
        updateInfoBar();
        queryEpg(c);
        chAdapter.setPlaying(c);
        bufferingSince = System.currentTimeMillis();
    }

    private void tryNextSource() {
        if (player != null && player.isPlaying()) return;
        if (curChannel == null || curChannel.urls.size() <= 1) {
            showLoading("播放失败,无更多源可切换");
            return;
        }
        curUrlIndex = (curUrlIndex + 1) % curChannel.urls.size();
        String name = curUrlIndex < curChannel.urlNames.size() ? curChannel.urlNames.get(curUrlIndex) : "";
        toast("切换到线路 " + (curUrlIndex + 1) + (name.isEmpty() ? "" : " " + name));
        playChannel(curChannel, curUrlIndex);
    }

    private void saveIndexes() {
        int ci = categories.indexOf(curCategory);
        int chi = curCategory == null ? -1 : curCategory.channels.indexOf(curChannel);
        if (ci >= 0) Settings.get().saveCategoryIndex(ci);
        if (chi >= 0) Settings.get().saveChannelIndex(chi);
    }

    private void startWatchdog() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                int timeout = Settings.get().getTimeOut();
                if (timeout > 0 && bufferingSince > 0
                        && System.currentTimeMillis() - bufferingSince > timeout * 1000L
                        && curChannel != null && curChannel.urls.size() > 1) {
                    bufferingSince = 0;
                    tryNextSource();
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ---------- zap ----------

    private void zap(int dir) {
        if (flatChannels.isEmpty()) return;
        int d = Settings.get().isReverseSwitchStyle() ? -dir : dir;
        if (Settings.get().isSwitchChannelInAll()) {
            flatIdx = ((flatIdx < 0 ? 0 : flatIdx) + d + flatChannels.size()) % flatChannels.size();
            playChannel(flatChannels.get(flatIdx), curUrlIndexOf(flatChannels.get(flatIdx)));
        } else if (curCategory != null) {
            List<Channel> l = curCategory.channels;
            int i = l.indexOf(curChannel);
            i = ((i < 0 ? 0 : i) + d + l.size()) % l.size();
            playChannel(l.get(i), curUrlIndexOf(l.get(i)));
        }
    }

    private void playByNumber(String num) {
        for (Channel c : flatChannels) {
            if (c.id.equals(num)) {
                playChannel(c, curUrlIndexOf(c));
                return;
            }
        }
        toast("无此频道号: " + num);
    }

    // ---------- EPG / info ----------

    private void queryEpg(Channel c) {
        infoNow.setText("正在播放: --");
        infoNext.setText("下一节目: --");
        if (!Settings.get().isEpg()) return;
        String base = Settings.get().getEpgUrl();
        EpgClient.query(base, c.displayName(), items -> runOnUiThread(() -> {
            if (c != curChannel) return;
            EpgClient.EpgItem[] nn = EpgClient.nowNext(items);
            if (nn != null && nn[0] != null) {
                infoNow.setText("正在播放: " + nn[0].title);
                infoNext.setText(nn[1] != null ? "下一节目: " + nn[1].title : "下一节目: --");
            }
        }));
    }

    private void updateInfoBar() {
        if (curChannel == null) return;
        infoNum.setText(curChannel.id);
        infoName.setText(curChannel.displayName());
        String ln = "线路 " + (curUrlIndex + 1) + "/" + curChannel.urls.size();
        if (curUrlIndex < curChannel.urlNames.size() && !curChannel.urlNames.get(curUrlIndex).isEmpty()) {
            ln += " " + curChannel.urlNames.get(curUrlIndex);
        }
        infoLine.setText(ln);
    }

    private void rootTick() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                infoTime.setText(clockFmt.format(new Date()));
                updateProgress();
                if (Settings.get().isShowNetSpeed() && bandwidthMeter != null) {
                    long est = bandwidthMeter.getBitrateEstimate() / 8;
                    infoSpeed.setText(String.format(Locale.US, "%.0f KB/s", est / 1024f));
                } else {
                    infoSpeed.setText("");
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void showInfoBar() {
        infoBar.setVisibility(View.VISIBLE);
        updateProgress();
    }

    private void updateProgress() {
        boolean show = false;
        if (player != null && infoBar.getVisibility() == View.VISIBLE
                && !player.isCurrentMediaItemLive() && player.isCurrentMediaItemSeekable()) {
            long dur = player.getDuration();
            if (dur > 0 && dur != C.TIME_UNSET) {
                long pos = pendingSeek != 0 && seekBase >= 0
                        ? seekBase + pendingSeek : player.getCurrentPosition();
                progBar.setMax((int) (dur / 1000));
                progBar.setProgress((int) (Math.min(pos, dur) / 1000));
                long buf = player.getBufferedPosition();
                progBar.setSecondaryProgress((int) (Math.min(buf, dur) / 1000));
                progCur.setText(fmtTime(pos));
                progDur.setText(fmtTime(dur));
                show = true;
            }
        }
        progressRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static String fmtTime(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return s >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
                : String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
    }

    private void scheduleInfoHide() {
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 4000);
    }

    private final Runnable hideInfo = () -> {
        if (channelPanel.getVisibility() != View.VISIBLE) infoBar.setVisibility(View.GONE);
        progressRow.setVisibility(View.GONE);
        inputNum.setVisibility(View.GONE);
    };

    private void toggleInfoBar() {
        if (infoBar.getVisibility() == View.VISIBLE) {
            infoBar.setVisibility(View.GONE);
            progressRow.setVisibility(View.GONE);
        } else {
            showInfoBar();
            scheduleInfoHide();
        }
    }

    private void showLoading(String msg) {
        loading.setVisibility(View.VISIBLE);
        loadingText.setText(msg);
    }

    // ---------- channel panel ----------

    private void showPanel() {
        channelPanel.setVisibility(View.VISIBLE);
        showInfoBar();
        int ci = categories.indexOf(curCategory);
        catAdapter.setSelected(Math.max(ci, 0));
        selectCategory(Math.max(ci, 0));
        focusCategory();
    }

    private void hidePanel() {
        channelPanel.setVisibility(View.GONE);
        scheduleInfoHide();
    }

    private void selectCategory(int pos) {
        if (pos < 0 || pos >= categories.size()) return;
        catAdapter.setSelected(pos);
        Category cat = categories.get(pos);
        curList.clear();
        curList.addAll(cat.name.equals("我的收藏") ? collectFavorites() : cat.channels);
        chAdapter.setItems(curList);
        chAdapter.setPlaying(curChannel);
        int pi = curList.indexOf(curChannel);
        if (pi >= 0) channelList.scrollToPosition(pi);
    }

    private void focusCategory() {
        panelChannelsFocused = false;
        int pos = catAdapter.getSelected();
        final int p = Math.max(pos, 0);
        categoryList.requestFocus();
        categoryList.post(() -> {
            RecyclerView.ViewHolder vh = categoryList.findViewHolderForAdapterPosition(p);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private void focusChannel() {
        panelChannelsFocused = true;
        int pos = curList.indexOf(curChannel);
        final int p = Math.max(pos, 0);
        channelList.requestFocus();
        channelList.post(() -> {
            RecyclerView.ViewHolder vh = channelList.findViewHolderForAdapterPosition(p);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private List<Channel> collectFavorites() {
        List<Channel> favs = new ArrayList<>();
        for (Category c : categories) for (Channel ch : c.channels) if (ch.favored) favs.add(ch);
        return favs;
    }

    private void toggleFavorite(Channel c) {
        c.favored = !c.favored;
        Settings.get().setChannelFavored(c, c.favored);
        chAdapter.notifyDataSetChanged();
        toast(c.favored ? "已收藏 " + c.displayName() : "已取消收藏 " + c.displayName());
    }

    // ---------- keys ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;

        // 媒体控制键：任何状态下生效
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && e.getRepeatCount() == 0) togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (down && e.getRepeatCount() == 0) setPaused(false);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0) setPaused(true);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) accumSeek(15000, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) accumSeek(-15000, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (down && e.getRepeatCount() == 0) zap(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (down && e.getRepeatCount() == 0) zap(-1);
                return true;
        }

        if (!down) {
            // 确认键短按=开菜单(UP时执行),长按=暂停(DOWN repeat时执行)
            if (e.getAction() == KeyEvent.ACTION_UP
                    && channelPanel.getVisibility() != View.VISIBLE
                    && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                        || code == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (!centerHeld) {
                    if (!numBuf.isEmpty()) commitNum(); else showPanel();
                }
                centerHeld = false;
                return true;
            }
            return super.dispatchKeyEvent(e);
        }

        if (channelPanel.getVisibility() == View.VISIBLE) return panelKey(code, e) || super.dispatchKeyEvent(e);

        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            numBuf += (code - KeyEvent.KEYCODE_0);
            if (numBuf.length() > 4) numBuf = numBuf.substring(numBuf.length() - 4);
            inputNum.setText(numBuf);
            inputNum.setVisibility(View.VISIBLE);
            handler.removeCallbacks(numCommit);
            handler.postDelayed(numCommit, 1500);
            return true;
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0) {
                    if (!centerHeld) togglePause();
                    centerHeld = true;
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                accumSeek(-15000, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                accumSeek(15000, e.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_MENU:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (Settings.get().isQuickExitStyle()) {
                    finish();
                } else {
                    toast("再按一次退出");
                    handler.postDelayed(() -> exitArmed = false, 2000);
                    if (exitArmed) finish();
                    exitArmed = true;
                }
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean exitArmed = false;
    private boolean centerHeld = false;
    private long pendingSeek = 0;
    private long seekBase = -1;

    private boolean panelKey(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                View f = getCurrentFocus();
                boolean inCat = f != null && isDescendant(categoryList, f);
                boolean inCh = f != null && isDescendant(channelList, f);
                if (!inCat && !inCh) {
                    if (panelChannelsFocused) focusChannel(); else focusCategory();
                    return true;
                }
                panelChannelsFocused = inCh;
                return false;
            }
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!panelChannelsFocused) focusChannel();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (panelChannelsFocused) focusCategory();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0) return true;
                if (!panelChannelsFocused) {
                    focusChannel();
                    return true;
                }
                return false; // RecyclerView item click handles
            case KeyEvent.KEYCODE_MENU:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case KeyEvent.KEYCODE_BACK:
                hidePanel();
                return true;
        }
        return false;
    }

    private static boolean isDescendant(View parent, View child) {
        while (child != null) {
            if (child == parent) return true;
            if (!(child.getParent() instanceof View)) return false;
            child = (View) child.getParent();
        }
        return false;
    }

    private final Runnable numCommit = this::commitNum;

    private void commitNum() {
        if (numBuf.isEmpty()) return;
        playByNumber(numBuf);
        numBuf = "";
        inputNum.setVisibility(View.GONE);
    }

    private void togglePause() {
        if (player == null) return;
        if (player.getPlayWhenReady()) {
            player.pause();
            showInfoBar();
            scheduleInfoHide();
            toast("暂停");
        } else {
            if (player.isCurrentMediaItemLive()) player.seekToDefaultPosition();
            player.play();
        }
    }

    private void setPaused(boolean paused) {
        if (player == null) return;
        if (paused == !player.getPlayWhenReady()) return;
        togglePause();
    }

    // 快进/快退:累计偏移+松手统一提交;按住自动加速(15s/跳→120s/跳)
    private void accumSeek(long baseStep, int repeatCount) {
        if (player == null) return;
        if (!player.isCurrentMediaItemSeekable() || player.isCurrentMediaItemLive()) return;
        long dur = player.getDuration();
        if (dur <= 0 || dur == C.TIME_UNSET) return;
        long step = baseStep * Math.min(1 + repeatCount, 8);
        if (pendingSeek == 0) seekBase = player.getCurrentPosition();
        long target = Math.max(0, Math.min(seekBase + pendingSeek + step, dur));
        pendingSeek = target - seekBase;
        showInfoBar();
        scheduleInfoHide();
        handler.removeCallbacks(seekCommit);
        handler.postDelayed(seekCommit, 600);
    }

    private final Runnable seekCommit = () -> {
        if (player != null && pendingSeek != 0 && seekBase >= 0) {
            player.seekTo(seekBase + pendingSeek);
        }
        pendingSeek = 0;
        seekBase = -1;
    };

    // ---------- misc ----------

    private int mapScale(int mode) {
        switch (mode) {
            case 1: return AspectRatioFrameLayout.RESIZE_MODE_FILL;
            case 2: return AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
            case 3: return AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH;
            case 4: return AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT;
            default: return AspectRatioFrameLayout.RESIZE_MODE_FIT;
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    public void onPlaylistReload() {
        numBuf = "";
        loadPlaylist();
    }

    @Override
    protected void onResume() {
        super.onResume();
        eyeOverlay.setVisibility(Settings.get().isEyeProtectMode() ? View.VISIBLE : View.GONE);
        if (player != null) {
            playerView.setResizeMode(mapScale(Settings.get().getScreenScale()));
            player.setPlayWhenReady(true);
        }
        if (App.playlistDirty) {
            App.playlistDirty = false;
            loadPlaylist();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null && curChannel != null) {
            Settings.get().saveSeekPos(curChannel.key(), player.getCurrentPosition());
            saveIndexes();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
