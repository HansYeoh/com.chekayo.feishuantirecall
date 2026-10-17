package com.chekayo.feishuantirecall;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

/** Message identity/ownership and the bounded foreground-content fallback. */
final class RecallMessages {
    interface AccountReader { String read() throws Throwable; }
    static AccountReader accountReader;
    static volatile boolean applicationReady;
    static RecallMarkStore markerStore;
    private static synchronized RecallMarkStore markers() {
        if (markerStore == null) markerStore = new RecallMarkStore(new java.io.File(AntiRecall.larkFilesDir(), "recall_marks"));
        return markerStore;
    }
    private static void remember(String uid, String chat, String id) {
        try { markers().add(uid, chat, id); } catch (Throwable ignored) {}
    }
    static void restoreAccounts() {
        try {
            final RecallMarkStore store = markers();
            store.afterLoad(new Runnable() {
                public void run() {
                    for (String uid : store.knownAccounts()) bindStorage(uid);
                }
            });
        } catch (Throwable ignored) {}
    }
    private static final java.util.Set<String> boundAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> queuedAccounts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile long lastRefresh;
    private static final int MAX_CONTENTS = 256;
    private static final Map<String, Entry> contents = new LinkedHashMap<String, Entry>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, RecallMessages.Entry> e) { return size() > MAX_CONTENTS; }
    };
    private static final class Entry {
        final Object content;
        boolean recalled;
        Entry(Object content) { this.content = content; }
    }

    static void install(ClassLoader cl) throws Throwable {
        final Class<?> passport = cl.loadClass("com.ss.android.lark.passport_api.IPassportApi");
        final Method getApi = cl.loadClass("com.ss.android.lark.utils.ApiUtils").getMethod("getApi", Class.class);
        final Method getUid = passport.getMethod("getUserId");
        accountReader = new AccountReader() {
            @Override public String read() throws Throwable {
                // Resolve the current service each time; a saved service/currentUid can
                // belong to the previous login after an in-process account switch.
                Object api = getApi.invoke(null, passport);
                return api == null ? "" : (String) getUid.invoke(api);
            }
        };
        // Storage initialization must not disable the legacy identity fallback.
        try { markers(); } catch (Throwable ignored) {}
        Class<?> info = cl.loadClass("com.ss.android.lark.chat.entity.message.MessageInfo");
        Class<?> message = cl.loadClass("com.ss.android.lark.chat.entity.message.Message");
        for (Method method : info.getDeclaredMethods()) {
            if (!method.getName().equals("getMessage") || method.getParameterTypes().length != 0
                    || method.getReturnType() != message || method.isBridge()) continue;
            HookRuntime.hook(method, "recall.messageInfo", new XposedInterface.Hooker() {
                @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object result = chain.proceed();
                    prepare(result);
                    return result;
                }
            });
        }
    }

    static long positive(String s) {
        if (s == null || s.isEmpty()) return 0;
        long n = 0;
        for (int i = 0; i < s.length(); ++i) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9 || n > (Long.MAX_VALUE - d) / 10) return 0;
            n = n * 10 + d;
        }
        return n;
    }
    static String account() {
        // Never initialize Passport during attachBaseContext: its static activity
        // manager requires the host Application to have finished initialization.
        if (!applicationReady) return "";
        try {
            String uid = accountReader == null ? "" : accountReader.read();
            if (positive(uid) == 0) return "";
            final String activeUid = uid;
            if (!boundAccounts.contains(uid) && queuedAccounts.add(uid)) {
                try { markers().afterLoad(new Runnable() {
                    public void run() {
                        try { bindStorage(activeUid); } finally { queuedAccounts.remove(activeUid); }
                    }
                }); } catch (Throwable ignored) { queuedAccounts.remove(uid); }
            }
            return uid;
        } catch (Throwable ignored) { return ""; }
    }
    static void refreshAccount() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < 2000) return;
        lastRefresh = now;
        account();
    }
    private static void bindStorage(String uid) {
        if (boundAccounts.contains(uid)) return;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(uid.getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.US, "%02x", b & 255));
            java.io.File sdk = new java.io.File(AntiRecall.larkFilesDir().getParentFile(), "databases/sdk_storage");
            String storage = hex.toString();
            if (!new java.io.File(sdk, storage).isDirectory()) {
                storage = uid;
                if (!new java.io.File(sdk, storage).isDirectory()) return;
            }
            AntiRecall.nativeBindRecallAccount(uid, storage);
            boundAccounts.add(uid);
            ModuleLog.log("[fucklark] recall account: verified SDK storage mapping");
        } catch (Throwable ignored) {}
    }
    static String value(Object object, String getter) throws Throwable {
        Object value = Reflect.callMethod(object, getter);
        return value == null ? "" : String.valueOf(value);
    }
    static String key(String uid, String chat, String id) {
        return positive(uid) > 0 && positive(chat) > 0 && positive(id) > 0 ? uid + ":" + chat + ":" + id : null;
    }
    static boolean marked(String uid, String chat, String id) {
        String key = key(uid, chat, id);
        if (key == null) return false;
        synchronized (contents) {
            Entry entry = contents.get(key);
            if (entry != null && entry.recalled) return true;
        }
        try { if (markers().contains(uid, chat, id)) return true; } catch (Throwable ignored) {}
        try {
            if (!AntiRecall.nativeIsRecallMarked(uid, positive(chat), positive(id))) return false;
            remember(uid, chat, id);
            return true;
        }
        catch (Throwable ignored) { return false; }
    }
    static void clear() { synchronized (contents) { contents.clear(); } }
    static boolean markedMessage(String uid, String id) {
        if (positive(uid) == 0 || positive(id) == 0) return false;
        synchronized (contents) {
            for (Map.Entry<String, Entry> e : contents.entrySet()) {
                if (e.getValue().recalled && e.getKey().startsWith(uid + ":")
                        && e.getKey().endsWith(":" + id)) return true;
            }
        }
        try { return markers().containsMessage(uid, id); }
        catch (Throwable ignored) { return false; }
    }

    static void prepare(Object message) {
        if (message == null || !Config.antirecall || AntiRecall.TAMPER == 2) return;
        try {
            String uid = account(), chat = value(message, "getChatId"), id = value(message, "getId");
            String key = key(uid, chat, id);
            if (key == null) return;
            String sender = value(message, "getFromId");
            Object self = Reflect.callMethod(message, "isFromMe");
            // Both ownership signals must agree before we retain somebody else's
            // content. Unknown/inconsistent identities always follow native behavior.
            if (!Boolean.FALSE.equals(self) || positive(sender) == 0 || uid.equals(sender)) {
                synchronized (contents) { contents.remove(key); }
                return;
            }
            boolean recalled = Boolean.TRUE.equals(Reflect.callMethod(message, "isRecalled"));
            if (!recalled) {
                Object content = Reflect.callMethod(message, "getContent");
                if (content != null) synchronized (contents) {
                    Entry old = contents.get(key);
                    Entry next = new Entry(content);
                    next.recalled = old != null && old.recalled;
                    contents.put(key, next);
                }
                return;
            }
            Entry entry;
            synchronized (contents) { entry = contents.get(key); }
            if (entry == null) return; // never substitute an unrelated notification
            Object status = Reflect.callMethod(message, "getStatus");
            if (!(status instanceof Enum)) return;
            Object normal = Reflect.getStaticObjectField(status.getClass(), "NORMAL");
            Reflect.callMethod(message, "setMessageContent", entry.content);
            Reflect.callMethod(message, "setStatus", normal);
            synchronized (contents) { entry.recalled = true; }
            remember(uid, chat, id);
        } catch (Throwable ignored) { /* Host models vary; leave the original untouched. */ }
    }
}
