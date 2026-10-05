package com.chekayo.feishuantirecall;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import io.github.libxposed.api.XposedInterface;

/** Exports and restores module-owned configuration and persistent archives through Android SAF. */
public final class DataMigration {
    private static final int REQ_EXPORT = 0x6F31;
    private static final int REQ_IMPORT = 0x6F32;
    private static final long MAX_BACKUP_BYTES = 32L * 1024L * 1024L;
    private static final int BUF_SIZE = 32 * 1024;
    private static volatile boolean installed;
    private static volatile int pendingRequest;

    private DataMigration() {}

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            HookRuntime.hookAllMethods(Activity.class, "onActivityResult", "migration.onActivityResult",
                    new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // legacy after-hook：原方法先执行，分发失败不影响原返回值
                    Object result = chain.proceed();
                    Object[] args = chain.getArgs().toArray();
                    int request = (Integer) args[0];
                    if ((request != REQ_EXPORT && request != REQ_IMPORT) || request != pendingRequest) return result;
                    pendingRequest = 0;
                    int resultCode = (Integer) args[1];
                    Intent data = (Intent) args[2];
                    if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return result;
                    Activity activity = (Activity) chain.getThisObject();
                    if (request == REQ_EXPORT) exportAsync(activity, data.getData());
                    else importAsync(activity, data.getData());
                    return result;
                }
            });
        } catch (Throwable t) {
            installed = false;
            ModuleLog.log("[fucklark] migration result hook failed: " + t);
        }
    }

    public static void chooseExport(Context context) {
        Activity activity = activityOf(context);
        if (activity == null) { toast(context, "无法取得当前页面，不能打开文件选择器"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            String day = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            i.putExtra(Intent.EXTRA_TITLE, "FeishuKit-backup-" + day + ".zip");
            pendingRequest = REQ_EXPORT;
            activity.startActivityForResult(i, REQ_EXPORT);
        } catch (Throwable t) {
            pendingRequest = 0;
            toast(context, "打开导出位置失败: " + safeMessage(t));
        }
    }

    public static void chooseImport(Context context) {
        Activity activity = activityOf(context);
        if (activity == null) { toast(context, "无法取得当前页面，不能打开文件选择器"); return; }
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            pendingRequest = REQ_IMPORT;
            activity.startActivityForResult(i, REQ_IMPORT);
        } catch (Throwable t) {
            pendingRequest = 0;
            toast(context, "打开备份文件失败: " + safeMessage(t));
        }
    }

    private static void exportAsync(final Activity activity, final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    List<FileItem> files = collect(activity.getFilesDir());
                    OutputStream raw = activity.getContentResolver().openOutputStream(uri, "w");
                    if (raw == null) throw new Exception("目标文件不可写");
                    ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw));
                    try {
                        JSONObject manifest = new JSONObject();
                        manifest.put("format", 1);
                        manifest.put("moduleVersion", AntiRecall.MODULE_VERSION);
                        manifest.put("createdAt", System.currentTimeMillis());
                        manifest.put("sourcePackage", activity.getPackageName());
                        putBytes(zip, "manifest.json", manifest.toString(2).getBytes("UTF-8"));
                        byte[] buf = new byte[BUF_SIZE];
                        for (FileItem item : files) putFile(zip, item.file, item.path, buf);
                    } finally { zip.close(); }
                    done(activity, "备份完成，共 " + files.size() + " 个数据文件");
                } catch (Throwable t) {
                    ModuleLog.log("[fucklark] export backup failed: " + t);
                    done(activity, "备份失败: " + safeMessage(t));
                }
            }
        }, "fucklark-backup-export").start();
    }

    private static void importAsync(final Activity activity, final Uri uri) {
        new Thread(new Runnable() {
            @Override public void run() {
                File stage = new File(activity.getCacheDir(), "fucklark_restore_" + System.currentTimeMillis());
                try {
                    if (!stage.mkdirs()) throw new Exception("无法创建导入临时目录");
                    InputStream raw = activity.getContentResolver().openInputStream(uri);
                    if (raw == null) throw new Exception("备份文件不可读");
                    Set<String> extracted = extractValidated(raw, stage);
                    if (!extracted.contains("manifest.json")) throw new Exception("不是有效的 FeishuKit 备份");
                    JSONObject manifest = new JSONObject(readText(new File(stage, "manifest.json")));
                    if (manifest.optInt("format", 0) != 1) throw new Exception("不支持的备份格式");
                    validateJsonFiles(stage, extracted);
                    File root = activity.getFilesDir();
                    int restored = 0;
                    for (String path : extracted) {
                        if ("manifest.json".equals(path)) continue;
                        atomicCopy(new File(stage, path), new File(root, path));
                        restored++;
                    }
                    Config.setFilesDir(root);
                    Config.load();
                    done(activity, "恢复完成，共 " + restored + " 个数据文件；建议重启飞书");
                } catch (Throwable t) {
                    ModuleLog.log("[fucklark] import backup failed: " + t);
                    done(activity, "恢复失败，未通过校验: " + safeMessage(t));
                } finally { deleteTree(stage); }
            }
        }, "fucklark-backup-import").start();
    }

    private static List<FileItem> collect(File root) {
        List<FileItem> out = new ArrayList<FileItem>();
        addIfFile(out, root, "fucklark_cfg.json");
        addIfFile(out, root, "notif_archive.txt");
        addIfFile(out, root, "leave_log.txt");
        File[] top = root.listFiles();
        if (top != null) for (File f : top) {
            if (f.isFile() && f.getName().startsWith("kicked_") && f.getName().endsWith(".txt"))
                out.add(new FileItem(f, f.getName()));
        }
        addIfFile(out, root, "resign_tracker/resigned_all.json");
        addIfFile(out, root, "resign_tracker/profiles.json");
        return out;
    }

    private static void addIfFile(List<FileItem> out, File root, String path) {
        File f = new File(root, path);
        if (f.isFile()) out.add(new FileItem(f, path));
    }

    private static boolean allowed(String path) {
        if ("manifest.json".equals(path) || "fucklark_cfg.json".equals(path)
                || "notif_archive.txt".equals(path) || "leave_log.txt".equals(path)
                || "resign_tracker/resigned_all.json".equals(path)
                || "resign_tracker/resigned_latest.json".equals(path)
                || "resign_tracker/profiles.json".equals(path)) return true;
        return path.startsWith("kicked_") && path.endsWith(".txt") && path.indexOf('/') < 0;
    }

    private static Set<String> extractValidated(InputStream raw, File stage) throws Exception {
        Set<String> names = new HashSet<String>();
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw));
        long total = 0;
        byte[] buf = new byte[BUF_SIZE];
        try {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (e.isDirectory()) continue;
                if (!allowed(name) || name.startsWith("/") || name.contains("../") || !names.add(name))
                    throw new Exception("备份包含非法文件: " + name);
                File dst = new File(stage, name);
                File parent = dst.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("无法创建临时目录");
                OutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
                try {
                    int n;
                    while ((n = zip.read(buf)) != -1) {
                        total += n;
                        if (total > MAX_BACKUP_BYTES) throw new Exception("备份超过 32MB 限制");
                        out.write(buf, 0, n);
                    }
                } finally { out.close(); }
                zip.closeEntry();
            }
        } finally { zip.close(); }
        return names;
    }

    private static void validateJsonFiles(File stage, Set<String> names) throws Exception {
        for (String name : names) {
            if (!name.endsWith(".json")) continue;
            try {
                String json = readText(new File(stage, name)).trim();
                if (json.startsWith("{")) new JSONObject(json);
                else if (json.startsWith("[")) new JSONArray(json);
                else throw new Exception("JSON 顶层必须是对象或数组");
            }
            catch (Throwable t) { throw new Exception("JSON 数据损坏: " + name); }
        }
    }

    private static void atomicCopy(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new Exception("无法创建目标目录");
        File tmp = new File(parent, dst.getName() + ".restore.tmp");
        copy(src, tmp);
        if (dst.exists() && !dst.delete()) { tmp.delete(); throw new Exception("无法替换 " + dst.getName()); }
        if (!tmp.renameTo(dst)) { copy(tmp, dst); tmp.delete(); }
    }

    private static void copy(File src, File dst) throws Exception {
        InputStream in = new BufferedInputStream(new FileInputStream(src));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(dst));
        byte[] buf = new byte[BUF_SIZE];
        try { int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }
        finally { try { in.close(); } finally { out.close(); } }
    }

    private static void putFile(ZipOutputStream zip, File file, String path, byte[] buf) throws Exception {
        zip.putNextEntry(new ZipEntry(path));
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        try { int n; while ((n = in.read(buf)) != -1) zip.write(buf, 0, n); }
        finally { in.close(); zip.closeEntry(); }
    }

    private static void putBytes(ZipOutputStream zip, String path, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
    }

    private static String readText(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        try { int n; while ((n = in.read(buf)) != -1) out.write(buf, 0, n); }
        finally { in.close(); }
        return new String(out.toByteArray(), "UTF-8");
    }

    private static Activity activityOf(Context context) {
        Context c = context;
        for (int i = 0; i < 10 && c != null; i++) {
            if (c instanceof Activity) return (Activity) c;
            if (!(c instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) c).getBaseContext();
            if (next == c) break;
            c = next;
        }
        return null;
    }

    private static void done(final Activity a, final String text) {
        a.runOnUiThread(new Runnable() { @Override public void run() { toast(a, text); } });
    }

    private static void toast(Context c, String text) { Toast.makeText(c, text, Toast.LENGTH_LONG).show(); }
    private static String safeMessage(Throwable t) { return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(); }
    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }

    private static final class FileItem {
        final File file; final String path;
        FileItem(File file, String path) { this.file = file; this.path = path; }
    }
}
