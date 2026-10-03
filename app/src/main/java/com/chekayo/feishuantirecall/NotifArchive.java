package com.chekayo.feishuantirecall;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * FeishuKit 后台消息存档 —— 记录【被撤回】消息原文，并支持聊天内还原显示。
 *
 * 为什么需要: 客户端离线/后台期间对方撤回, 撤回发生在服务器; 上线同步时服务器可能只下发
 * 「已撤回」空壳, 原文根本不进本地库 -> SQL 层防撤回无能为力(防撤回场景 B)。但原文曾到过
 * 设备——飞书用它弹过通知。故 hook NotificationManager.notify:
 *   1) 普通消息 → 进内存缓冲(不落盘);
 *   2) 「X 撤回了一条消息」→ 按发送人从缓冲回捞原文, 落盘存档 + 记入还原表;
 *   3) 聊天 UI: 仅当发送人严格匹配到存档时, 把系统提示整段替换为原文;
 *      否则显示 Config.recallHintText（默认「撤回了一条消息」），绝不把存档最新一条当提示。
 *
 * 前提: 飞书通知开着「消息预览」。仅文本可靠。
 * 局限: 按发送人关联; 群里同人连发多条时只能捞最近一条; 静默同步撤回则捞不到。
 *
 * 跨进程: 飞书各进程 hook + 读同一份配置; 桌面统计通过 ArchiveSync 推到模块目录。
 */
public class NotifArchive {
    static volatile File FILE;
    static final int CAP = 256 * 1024;   // 256KB 上限
    static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    // 近期消息缓冲(内存, 不落盘)
    static final int BUF = 80;
    static final long BUF_WINDOW = 10 * 60 * 1000L;   // 10 分钟
    /** 还原表时效: 只认 30 分钟内的条目, 防止旧存档原文被当成新撤回的消息显示。 */
    static final long RESTORE_WINDOW_MS = 30 * 60 * 1000L;
    static final java.util.ArrayList<String[]> recent = new java.util.ArrayList<String[]>();  // {timeMs, sender, body}
    static volatile String lastSaved = "";
    static volatile long   lastSavedTime = 0;

    // 撤回原文还原表: sender -> {body, time}；聊天 UI 严格按发送人匹配
    static final int RESTORE_MAX = 50;
    static final java.util.LinkedHashMap<String, String[]> restoreMap =
            new java.util.LinkedHashMap<String, String[]>(32, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String[]> e) {
                    return size() > RESTORE_MAX;
                }
            };
    static volatile boolean restoredFromFile = false;

    // 开关热读节流
    static volatile long lastCfgReload = 0;

    public static void setFilesDir(File filesDir) {
        if (filesDir == null) return;
        // 通知存档按登录账号隔离。setFilesDir 在 handleLoadPackage 早期执行, 可能早于 AccountPaths.bind,
        // 此处自行探测 uid(无 Context 也走 /data/data/<pkg> 路径), 否则 FILE 会固定到 accounts/unknown/
        // 而迁移/桌面推送在 accounts/<uid>/ —— 写读分离导致「后台消息存档失效」。
        String uid = AccountPaths.currentUid;
        if (uid == null || uid.isEmpty()) {
            uid = AccountPaths.detectUid(null);
            if (uid != null && !uid.isEmpty()) AccountPaths.currentUid = uid;
        }
        if (uid == null || uid.isEmpty()) uid = AccountPaths.FALLBACK_UID;
        File acc = new File(filesDir, "accounts/" + AccountPaths.safeUid(uid));
        FILE = new File(acc, "notif_archive.txt");
        restoredFromFile = false;
        try { android.util.Log.i("fucklark", "NotifArchive FILE=" + FILE); } catch (Throwable ignored) {}
    }

    /** 解析消息文件（读优先级：当前账号 → 其它账号 → 旧全局路径）。 */
    static File resolveArchiveFile() {
        if (FILE != null && FILE.exists()) return FILE;
        return AccountPaths.resolveMessageFile(null, null, AccountPaths.currentUid, "notif_archive.txt");
    }

    /** 由 NotificationManager.notify hook 调。 */
    public static void capture(Notification n) {
        try {
            if (n == null) return;
            long now = System.currentTimeMillis();
            if (now - lastCfgReload > 3000) {
                lastCfgReload = now;
                try { Config.load(); } catch (Throwable ignored) {}
            }
            if (!Config.notifarchive) return;
            if (FILE == null) return;

            int f = n.flags;
            if ((f & Notification.FLAG_ONGOING_EVENT) != 0) return;
            if ((f & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
            if ((f & Notification.FLAG_GROUP_SUMMARY) != 0) return;

            Bundle ex = n.extras;
            if (ex == null) return;
            String title = cs(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text  = cs(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            if (text.isEmpty()) text = cs(ex.getCharSequence(Notification.EXTRA_TEXT));

            String msgSender = "";
            try {
                Parcelable[] msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (msgs != null && msgs.length > 0 && msgs[msgs.length - 1] instanceof Bundle) {
                    Bundle mb = (Bundle) msgs[msgs.length - 1];
                    String mt = cs(mb.getCharSequence("text"));
                    String ms = cs(mb.getCharSequence("sender"));
                    if (!mt.isEmpty()) text = mt;
                    if (!ms.isEmpty()) msgSender = ms;
                }
            } catch (Throwable ignored) {}

            title = title.replace('\t', ' ').replace('\n', ' ').trim();
            String body = text.replace('\t', ' ').replace('\n', ' ').trim();
            if (body.isEmpty()) return;

            // ① 撤回通知 → 回捞该发送人最近一条，落盘 + 进还原表（统计与聊天还原共用）
            String rs = recallSender(body);
            if (rs != null) {
                String[] hit = takeRecent(rs, now);
                if (hit != null && hit[0] != null && !hit[0].isEmpty()) {
                    String sig = hit[0] + "|" + hit[1];
                    if (sig.equals(lastSaved) && (now - lastSavedTime) < 8000) return;
                    lastSaved = sig; lastSavedTime = now;
                    write(TS.format(new Date(now)) + "\t" + hit[0] + "\t" + hit[1] + "\n");
                    putRestore(hit[0], hit[1], now);
                }
                return;
            }

            // ② 普通消息 → 只进内存缓冲
            String sender = msgSender, msg = body;
            if (sender.isEmpty()) {
                int i = sepIdx(body);
                if (i > 0) { sender = body.substring(0, i).trim(); msg = body.substring(i + 1).trim(); }
                else sender = title;
            }
            if (msg.isEmpty()) return;
            addRecent(now, sender, msg);
        } catch (Throwable ignored) {}
    }

    static synchronized void putRestore(String sender, String body, long timeMs) {
        if (sender == null || sender.trim().isEmpty() || body == null || body.isEmpty()) return;
        restoreMap.put(sender.trim(), new String[]{ body, Long.toString(timeMs) });
    }

    /**
     * 按发送人查可还原的撤回原文（严格匹配 + 30 分钟时效）。
     * sender 为空或对不上 → null，禁止用「存档里最近一条」去顶提示/还原。
     * 时效: 还原表条目只在 RESTORE_WINDOW_MS 内有效 —— 前台聊天里的撤回不产生通知、
     * 不会写入还原表, 若无时效会命中该发送人更早的存档原文, 把旧消息当成本次撤回原文显示。
     */
    public static synchronized String findRestore(String sender) {
        return findRestore(sender, System.currentTimeMillis());
    }

    public static synchronized String findRestore(String sender, long nowMs) {
        try {
            if (!restoredFromFile) loadRestoreFromArchive();
            if (sender == null || sender.trim().isEmpty()) return null;
            String[] hit = restoreMap.get(sender.trim());
            if (hit != null && hit.length >= 2 && hit[0] != null && !hit[0].isEmpty()) {
                long ts = parseRestoreTs(hit[1]);
                if (ts > 0) {
                    long age = nowMs - ts;
                    if (age <= RESTORE_WINDOW_MS && age >= -60000L) return hit[0];
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 还原条目时间戳: 兼容 毫秒 / "yyyy-MM-dd HH:mm:ss" / "MM-dd HH:mm:ss"(补当前年)。解析不了返回 0。 */
    static long parseRestoreTs(String s) {
        if (s == null) return 0;
        String t = s.trim();
        try { return Long.parseLong(t); } catch (Throwable ignored) {}
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            f.setLenient(false);
            java.util.Date d = f.parse(t, new java.text.ParsePosition(0));
            if (d != null) return d.getTime();
        } catch (Throwable ignored) {}
        try {
            synchronized (TS) {
                java.util.Date d = TS.parse(t, new java.text.ParsePosition(0));
                if (d != null) {
                    java.util.Calendar c = java.util.Calendar.getInstance();
                    c.setTime(d);
                    c.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR));
                    long ms = c.getTimeInMillis();
                    if (ms > System.currentTimeMillis() + 2L * 86400000L) ms -= 365L * 86400000L;  // 跨年存档回退一年
                    return ms;
                }
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    /** 首次查还原时，从存档文件回灌最近记录（进程重启后仍可还原）。 */
    static void loadRestoreFromArchive() {
        restoredFromFile = true;
        try {
            File f = resolveArchiveFile();
            if (f == null || !f.exists()) return;
            String all = new String(readBytes(f), "UTF-8");
            if (all.isEmpty()) return;
            String[] lines = all.split("\n");
            int start = Math.max(0, lines.length - RESTORE_MAX);
            for (int i = start; i < lines.length; i++) {
                String ln = lines[i];
                if (ln == null || ln.isEmpty()) continue;
                String[] p = ln.split("\t", 3);
                if (p.length < 3) continue;
                String sender = p[1].trim();
                String body = p[2].trim();
                long ms = parseRestoreTs(p[0]);
                if (!sender.isEmpty() && !body.isEmpty() && ms > 0) {
                    restoreMap.put(sender, new String[]{ body, Long.toString(ms) });
                }
            }
        } catch (Throwable ignored) {}
    }

    /** 撤回系统通知的发送人（非撤回通知返回 null）。 */
    static String recallSender(String body) {
        if (body == null) return null;
        int i = body.indexOf("撤回了一条消息");
        if (i >= 0) return body.substring(0, i).trim();
        if (body.contains("消息已撤回") || body.contains("撤回了此消息") || body.contains("撤回了这条消息")) {
            int j = body.indexOf("撤回");
            if (j > 0) return body.substring(0, j).trim();
            return "";
        }
        String l = body.toLowerCase(Locale.US);
        int k = l.indexOf(" recalled a message");
        if (k < 0) k = l.indexOf(" unsent a message");
        if (k > 0) return body.substring(0, k).trim();
        return null;
    }

    static synchronized String[] takeRecent(String sender, long now) {
        if (sender == null || sender.trim().isEmpty()) return null;
        String key = sender.trim();
        for (int i = recent.size() - 1; i >= 0; i--) {
            String[] e = recent.get(i);
            if (now - Long.parseLong(e[0]) > BUF_WINDOW) continue;
            boolean match = e[1].equals(key) || e[1].contains(key) || key.contains(e[1]);
            if (match) { recent.remove(i); return new String[]{ e[1], e[2] }; }
        }
        return null;
    }

    static synchronized void addRecent(long now, String sender, String msg) {
        if (sender == null || sender.isEmpty() || msg == null || msg.isEmpty()) return;
        for (int i = recent.size() - 1; i >= 0 && i >= recent.size() - 3; i--) {
            String[] e = recent.get(i);
            if (e[1].equals(sender) && e[2].equals(msg)) { e[0] = Long.toString(now); return; }
        }
        recent.add(new String[]{ Long.toString(now), sender, msg });
        while (recent.size() > BUF) recent.remove(0);
    }

    static int sepIdx(String s) {
        int a = s.indexOf('：');
        int b = s.indexOf(':');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    static String cs(CharSequence c) { return c == null ? "" : c.toString(); }

    /** 落盘统计；成功后推副本到模块进程，供桌面「记录」显示条数。 */
    static synchronized void write(String line) {
        try {
            if (FILE == null) return;
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            long len = FILE.exists() ? FILE.length() : 0;
            if (len > CAP) truncate();
            FileOutputStream os = new FileOutputStream(FILE, true);
            os.write(line.getBytes("UTF-8"));
            os.flush();
            os.close();
            FILE.setReadable(true, false);
            try { ArchiveSync.pushNotifArchive(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    static void truncate() {
        try {
            if (FILE == null || !FILE.exists()) return;
            byte[] all = readBytes(FILE);
            int keep = all.length / 2;
            byte[] tailB = new byte[keep];
            System.arraycopy(all, all.length - keep, tailB, 0, keep);
            FileOutputStream os = new FileOutputStream(FILE, false);
            os.write("...(旧存档已截断)...\n".getBytes("UTF-8"));
            os.write(tailB);
            os.flush();
            os.close();
        } catch (Throwable ignored) {}
    }

    /** 解析存档路径：本进程 FILE → 飞书沙箱 → 模块目录（桌面统计）。 */
    public static synchronized String read() {
        try {
            File f = resolveArchiveFile();
            if (f == null || !f.exists()) return "";
            return new String(readBytes(f), "UTF-8");
        } catch (Throwable t) { return ""; }
    }

    /** 存档条数（统计用）。 */
    public static synchronized int count() {
        try {
            String s = read();
            if (s.isEmpty()) return 0;
            int c = 0;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') c++;
            return c;
        } catch (Throwable t) { return 0; }
    }

    public static synchronized void clear() {
        try {
            File f = resolveArchiveFile();
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) {}
        try {
            restoreMap.clear();
            restoredFromFile = false;
        } catch (Throwable ignored) {}
    }

    static byte[] readBytes(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int off = 0, r;
        while (off < b.length && (r = is.read(b, off, b.length - off)) > 0) off += r;
        is.close();
        return b;
    }
}
