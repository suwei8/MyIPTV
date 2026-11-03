package com.sw.iptv.ui;

import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.sw.iptv.core.Settings;
import com.sw.iptv.core.Updater;

import java.io.File;

/**
 * 更新弹窗与安装编排: 展示检查结果 → 下载进度 → 调起系统安装器。
 * 供 MainActivity(启动自动检查) 与 SettingsActivity(手动检查) 复用。
 */
public class UpdateFlow {

    private UpdateFlow() {
    }

    /** 发现新版本时的提示弹窗。 */
    public static void showUpdateDialog(final Activity act, final Updater.Release rel) {
        if (act.isFinishing()) return;
        String cur = Updater.currentVersionName(act);
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本: v").append(cur)
                .append("\n最新版本: v").append(rel.versionName).append("\n\n");
        if (rel.notes != null && !rel.notes.trim().isEmpty()) {
            msg.append(rel.notes.trim()).append("\n\n");
        }
        if ("debug".equalsIgnoreCase(rel.signed)) {
            msg.append("⚠ 该发布为 debug 签名, 无法覆盖安装, 需卸载后重装。");
        } else {
            msg.append("点“立即更新”将下载并覆盖安装(无需卸载)。");
        }
        new AlertDialog.Builder(act)
                .setTitle("发现新版本")
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", (d, w) -> startDownload(act, rel))
                .setNeutralButton("跳过此版本", (d, w) -> {
                    if (rel.versionCode > 0) Settings.get().setIgnoredUpdate(rel.versionCode);
                })
                .setNegativeButton("稍后", null)
                .show();
    }

    /** 检查结果弹窗(手动检查时用)。 */
    public static void showCheckResult(Activity act, Updater.Release rel, String error) {
        if (act.isFinishing()) return;
        String cur = Updater.currentVersionName(act);
        if (error != null) {
            new AlertDialog.Builder(act)
                    .setTitle("检查更新失败")
                    .setMessage("当前版本: v" + cur + "\n\n" + error)
                    .setPositiveButton("知道了", null)
                    .show();
        } else if (rel == null) {
            new AlertDialog.Builder(act)
                    .setTitle("检查更新")
                    .setMessage("当前已是最新版本 v" + cur)
                    .setPositiveButton("知道了", null)
                    .show();
        } else {
            showUpdateDialog(act, rel);
        }
    }

    private static void startDownload(final Activity act, final Updater.Release rel) {
        if (!Updater.canInstall(act)) {
            new AlertDialog.Builder(act)
                    .setTitle("需要安装权限")
                    .setMessage("系统需要你允许本应用“安装未知应用”。\n授权后请点击“继续”。")
                    .setPositiveButton("去授权", (d, w) -> Updater.openInstallSettings(act))
                    .setNeutralButton("继续", (d, w) -> startDownload(act, rel))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        showProgress(act, rel);
    }

    private static void showProgress(final Activity act, final Updater.Release rel) {
        if (act.isFinishing()) return;
        final ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        final TextView tv = new TextView(act);
        tv.setText("准备下载…");
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad / 2);
        box.addView(bar);
        box.addView(tv);
        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("正在下载更新")
                .setView(box)
                .setCancelable(false)
                .create();
        dlg.show();

        Updater.download(act, rel, (done, total) -> {
            if (total > 0) {
                int p = (int) (done * 100 / total);
                bar.setProgress(p);
                tv.setText("已下载 " + p + "%   (" + fmtSize(done) + " / " + fmtSize(total) + ")");
            } else {
                tv.setText("已下载 " + fmtSize(done));
            }
        }, (apk, error) -> {
            if (dlg.isShowing()) dlg.dismiss();
            if (act.isFinishing()) return;
            if (error != null) {
                new AlertDialog.Builder(act)
                        .setTitle("更新失败")
                        .setMessage(error)
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            Updater.install(act, apk);
        });
    }

    private static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.0fKB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024.0));
    }
}
