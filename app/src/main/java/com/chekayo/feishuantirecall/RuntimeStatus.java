package com.chekayo.feishuantirecall;

import android.os.Bundle;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider 内收集飞书进程心跳，用实际加载版本诊断未重启的旧模块。 */
final class RuntimeStatus {
    private static final Map<String, Bundle> PROCESSES = new LinkedHashMap<String, Bundle>();
    static synchronized void report(Bundle info) {
        if (info == null || info.getString("process") == null) return;
        Bundle copy = new Bundle(info);
        copy.putLong("seenAt", System.currentTimeMillis());
        PROCESSES.put(info.getString("process"), copy);
    }
    static synchronized String describe() {
        long authority = Config.updatedAt();
        StringBuilder out = new StringBuilder("已安装模块：v" + ModuleBuild.VERSION
                + " (" + ModuleBuild.VERSION_CODE + ")\n构建：" + ModuleBuild.BUILD
                + "\n配置修订：" + authority + "\n");
        if (PROCESSES.isEmpty()) out.append("\n尚未收到飞书进程状态，请启动或重启飞书。\n");
        for (Map.Entry<String, Bundle> entry : PROCESSES.entrySet()) {
            Bundle info = entry.getValue();
            long age = Math.max(0, System.currentTimeMillis() - info.getLong("seenAt")) / 1000;
            out.append("\n").append(entry.getKey()).append("\n实际加载：v")
                    .append(info.getString("version")).append(" · ").append(info.getString("build"))
                    .append("\n配置修订：").append(info.getLong("updatedAt"))
                    .append("\n最近同步：").append(age).append(" 秒前\n");
            if (!ModuleBuild.BUILD.equals(info.getString("build"))) out.append("模块已更新，请重启此应用以加载新版。\n");
            else if (age > 10) out.append("进程暂停或未运行，恢复运行后会自动同步。\n");
            else if (info.getLong("updatedAt") != authority) out.append("正在同步最新配置。\n");
            else out.append("版本与配置一致。\n");
        }
        return out.toString();
    }
    static void show(final android.content.Context ctx) {
        final android.widget.TextView text = new android.widget.TextView(ctx);
        text.setText("正在读取同步状态…"); text.setTextColor(Ui.titleColor(ctx));
        text.setTextIsSelectable(true); text.setTextSize(13);
        int p = Ui.dp(ctx, 16); text.setPadding(p, p, p, p);
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx); scroll.addView(text);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(ctx).setTitle("版本与配置同步").setView(scroll)
                .setPositiveButton("关闭", null).show();
        Ui.styleDialog(dialog);
        new Thread(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    Bundle b = ctx.getContentResolver().call(ConfigProvider.URI, "status", null, null);
                    result = b == null ? "无法读取模块配置源，请检查模块是否已安装。" : b.getString("text");
                } catch (Throwable t) { result = "同步状态读取失败：" + t; }
                if (!Config.MODULE_PKG.equals(ctx.getPackageName())) {
                    result += "\n当前进程加载：v" + ModuleBuild.VERSION + " · " + ModuleBuild.BUILD;
                    if (!Config.syncError().isEmpty()) result += "\n同步错误：" + Config.syncError();
                }
                final String display = result;
                text.post(new Runnable() { @Override public void run() { text.setText(display); } });
            }
        }, "config-status").start();
    }
    private RuntimeStatus() {}
}
