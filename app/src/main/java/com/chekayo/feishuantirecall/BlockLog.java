package com.chekayo.feishuantirecall;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** 最近 200 条屏蔽记录，由模块 Provider 汇总，桌面和飞书读取同一份记录。 */
final class BlockLog {
    private static final int LIMIT = 200;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Map<View, String> LAST = new WeakHashMap<View, String>();

    static void record(final String kind, View view, final String via) {
        final Context c = Config.appContextForSync();
        if (c == null || view == null) return;
        record(kind, view, via, BlockedContent.read(view));
    }

    static void record(final String kind, View view, final String via, final String content) {
        final Context c = Config.appContextForSync();
        if (c == null || view == null) return;
        long now = System.currentTimeMillis();
        String fingerprint = kind + "\n" + content;
        synchronized (LAST) {
            if (fingerprint.equals(LAST.get(view))) return;
            LAST.put(view, fingerprint);
        }
        final String widget = view.getClass().getName();
        String resource;
        try { resource = view.getResources().getResourceEntryName(view.getId()); }
        catch (Throwable ignored) { resource = ""; }
        final String id = resource;
        final long time = now;
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    JSONObject event = new JSONObject();
                    event.put("content", content);
                    event.put("contentStatus", content == null || content.isEmpty() ? "unavailable" : "visible");
                    event.put("time", time); event.put("kind", kind); event.put("via", via);
                    event.put("package", c.getPackageName()); event.put("widget", widget); event.put("id", id);
                    event.put("process", android.os.Build.VERSION.SDK_INT >= 28
                            ? android.app.Application.getProcessName() : c.getPackageName());
                    Bundle args = new Bundle(); args.putString("json", event.toString());
                    Bundle result = c.getContentResolver().call(ConfigProvider.URI, "blockLogAdd", null, args);
                    if (result == null || !result.getBoolean("ok")) sendEvent(c, event.toString());
                } catch (Throwable t) {
                    try {
                        JSONObject event = new JSONObject(); event.put("time", time); event.put("kind", kind);
                        event.put("content", content); event.put("via", via); event.put("id", id);
                        event.put("process", c.getPackageName());
                        sendEvent(c, event.toString());
                    } catch (Throwable ignored) { android.util.Log.w("fucklark", "屏蔽日志保存失败", t); }
                }
            }
        });
    }

    private static File file(Context c) { return new File(c.getFilesDir(), "block_log.json"); }
    static synchronized void appendLocal(Context c, String json) throws Exception {
        if (json == null) return;
        File f = file(c);
        JSONArray entries;
        try { entries = f.exists() ? new JSONArray(Config.read(f)) : new JSONArray(); }
        catch (Throwable invalid) { entries = new JSONArray(); }
        JSONObject event = new JSONObject(json);
        int replace = -1;
        for (int i = entries.length() - 1; i >= Math.max(0, entries.length() - 8); i--) {
            JSONObject previous = entries.optJSONObject(i);
            if (previous == null) continue;
            boolean same = previous.optString("kind").equals(event.optString("kind"))
                    && previous.optString("process").equals(event.optString("process"))
                    && previous.optString("id").equals(event.optString("id"))
                    && Math.abs(event.optLong("time") - previous.optLong("time")) < 5000;
            if (!same) continue;
            if (event.optString("content").isEmpty() && !previous.optString("content").isEmpty()) return;
            if (previous.optString("content").isEmpty() && !event.optString("content").isEmpty()) { replace = i; break; }
        }
        JSONArray trimmed = new JSONArray();
        int existing = entries.length() - (replace >= 0 ? 1 : 0);
        int skip = Math.max(0, existing - LIMIT + 1);
        for (int i = 0; i < entries.length(); i++) {
            if (i == replace) continue;
            if (skip > 0) { skip--; continue; }
            trimmed.put(entries.get(i));
        }
        trimmed.put(event);
        Config.write(f, trimmed.toString());
    }
    static synchronized String readLocal(Context c) throws Exception {
        File f = file(c);
        JSONArray entries = f.exists() ? new JSONArray(Config.read(f)) : new JSONArray();
        if (entries.length() == 0) return "暂无屏蔽记录。开启屏蔽功能并出现提示后会自动记录。";
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        StringBuilder out = new StringBuilder();
        for (int i = entries.length() - 1; i >= 0; i--) {
            if (out.length() > 180000) { out.append("较早记录未在本次展示，文件仍保留最近 200 条。\n"); break; }
            JSONObject event = entries.getJSONObject(i);
            out.append(format.format(new Date(event.optLong("time")))).append(" · ")
                    .append(event.optString("kind")).append("\n")
                    .append("屏蔽内容：\n").append(event.optString("content").isEmpty()
                            ? "此控件未提供可读取的文字（可能尚未布局或使用图片绘制）" : event.optString("content"))
                    .append("\n\n进程：").append(event.optString("process")).append("\n")
                    .append(event.optString("via")).append(" · ")
                    .append(event.optString("id")).append("\n\n");
        }
        return out.toString();
    }
    static void show(final Context ctx) {
        final TextView text = new TextView(ctx);
        text.setText("正在读取屏蔽日志…"); text.setTextColor(Ui.titleColor(ctx));
        text.setTextSize(13); text.setTextIsSelectable(true);
        int padding = Ui.dp(ctx, 16); text.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(ctx); scroll.addView(text);
        AlertDialog dialog = new AlertDialog.Builder(ctx).setTitle("屏蔽日志 · 最近 200 条").setView(scroll)
                .setPositiveButton("关闭", null).show();
        Ui.styleDialog(dialog);
        IO.execute(new Runnable() {
            @Override public void run() {
                String result;
                try {
                    Bundle b = ctx.getContentResolver().call(ConfigProvider.URI, "blockLogGet", null, null);
                    result = b != null && b.getString("text") != null ? b.getString("text") : readLocal(ctx);
                } catch (Throwable t) {
                    requestViaBroadcast(ctx, text);
                    return;
                }
                final String display = result;
                text.post(new Runnable() { @Override public void run() { text.setText(display); } });
            }
        });
    }
    private static void sendEvent(Context c, String json) throws Exception {
        // 不可发现 Provider 时，显式组件广播仍能跨应用传递。
        appendLocal(c, json);
        android.content.Intent i = new android.content.Intent("com.chekayo.feishuantirecall.BLOCK_LOG_ADD");
        i.setComponent(new android.content.ComponentName(Config.MODULE_PKG, BlockLog.Receiver.class.getName()));
        i.putExtra("json", json); c.sendBroadcast(i);
    }

    private static void requestViaBroadcast(Context c, final TextView text) {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean[] done = { false };
        final android.os.ResultReceiver reply = new android.os.ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                done[0] = true;
                text.setText(data != null && data.getString("text") != null
                        ? data.getString("text") : "未收到屏蔽记录，请启动桌面模块后重试。");
            }
        };
        android.content.Intent i = new android.content.Intent("com.chekayo.feishuantirecall.BLOCK_LOG_GET");
        i.setComponent(new android.content.ComponentName(Config.MODULE_PKG, BlockLog.Receiver.class.getName()));
        i.putExtra("reply", reply);
        try { c.sendBroadcast(i); }
        catch (Throwable t) { done[0] = true; text.setText("无法连接模块，请启动桌面模块后重试。"); }
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!done[0]) text.setText("模块暂无响应，请启动桌面模块后重试。");
            }
        }, 5000);
    }

    public static class Receiver extends android.content.BroadcastReceiver {
        @Override public void onReceive(final Context c, final android.content.Intent i) {
            final PendingResult pending = goAsync();
            IO.execute(new Runnable() {
                @Override public void run() {
                    try {
                        if ("com.chekayo.feishuantirecall.BLOCK_LOG_ADD".equals(i.getAction()))
                            appendLocal(c, i.getStringExtra("json"));
                        else if ("com.chekayo.feishuantirecall.BLOCK_LOG_GET".equals(i.getAction())) {
                            android.os.ResultReceiver reply = i.getParcelableExtra("reply");
                            if (reply != null) {
                                Bundle b = new Bundle(); b.putString("text", readLocal(c)); reply.send(0, b);
                            }
                        }
                    } catch (Throwable t) { android.util.Log.w("fucklark", "日志广播处理失败", t); }
                    finally { pending.finish(); }
                }
            });
        }
    }
    private BlockLog() {}
}
