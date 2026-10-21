package com.sw.iptv.core;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/**
 * 拉满式预拉控制: 只要堆内缓冲没到字节上限就一直全速拉流,
 * 不再看"领先播放头多少秒"——默认 LoadControl 缓冲一达标就停拉,
 * 造成网速显示0、快进没数据。字节上限防低配设备OOM,大文件由
 * MediaCache 预拉线程下到磁盘,与堆内缓冲解耦。
 */
@OptIn(markerClass = UnstableApi.class)
public class GreedyLoadControl extends DefaultLoadControl {

    private final int capBytes;

    /**
     * @param capBytes 堆内缓冲字节上限(压缩数据),达到后暂停拉流,随播放消耗自动恢复
     */
    public GreedyLoadControl(int capBytes) {
        super(new DefaultAllocator(true, 64 * 1024),
                50_000,             // minBufferMs: 卡顿恢复门槛(保持默认)
                Integer.MAX_VALUE,  // maxBufferMs: 时间不封顶,实际由字节上限兜底
                2_500,              // bufferForPlaybackMs: 起播门槛,保持默认
                5_000,              // bufferForPlaybackAfterRebufferMs
                capBytes,           // 字节上限(堆内)
                true,               // prioritizeTimeOverSizeThresholds
                0, false);          // backBuffer 关闭
        this.capBytes = capBytes;
    }

    @Override
    public boolean shouldContinueLoading(LoadControl.Parameters params) {
        return getAllocator().getTotalBytesAllocated() < capBytes;
    }
}
