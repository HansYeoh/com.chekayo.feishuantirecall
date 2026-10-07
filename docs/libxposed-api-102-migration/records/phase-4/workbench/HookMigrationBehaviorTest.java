package com.chekayo.feishuantirecall;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 阶段 4 出口验证：宿主 JVM 直跑迁移后 hook 回调的行为等价测试（不进 APK、不依赖真机）。
 * 覆盖（对应 05 文档统一转换原则与第四组验收重点）：
 * 1. ReadReqHook（构造器 hook，before 改参）：
 *    浏览路径清空 message_ids/fold_ids 并暂存；回复窗口路径合并回填；开关关时原参透传；
 *    短参数构造安全放行。
 * 2. SendReqHook（构造器 hook，纯副作用）：READ_WINDOW 开窗。
 * 9~15. v2.4 回复即已读·签名发现 + Kn 截获重放（feat/read-as-unread）：Kn 按签名形状唯一
 *    定位（过滤参数个数/回调类型/static/返回类型干扰项，双命中保守 null）；截获只落线程槽、
 *    由读请求构造按会话落账（KN_IDS 累加多包不丢/KN_CTX 模板+ids 样本）；重放时按样本关联
 *    定位 ids 访问器（区分双 List）→ 模板列表原地替换为累加全集 → 重放一次并清缓存；
 *    失败保留可重试、按会话隔离、开关关/通道未就绪不触发、KN_REPLAY 卫兵三直通、
 *    重放后载体兜底路径幂等。
 * 3. InvokeHook（ANTIREAD_DROP=false 现状）：任何命令都放行原调用，不短路。
 * 4. MapperHook（before 反射改对象）：文本入缓存；空文本时回填缓存内容 + 恢复 NORMAL 状态。
 * 5. RestrictedModeUnlock 共享门禁回调：开关开 -> 短路（boolean=false / void=null，原方法不执行）；
 *    开关关 -> proceed。
 * 6. 注册面：全部经 HookRuntime，幂等键去重（同 logicalId 二次安装 0 新框架安装）。
 */
public class HookMigrationBehaviorTest {

    static int failures = 0;

    // ── 伪造 Chain：proceed 记录取参，返回受控结果 ──────────────────────
    static class FakeChain implements XposedInterface.Chain {
        final Executable exe;
        final Object thisObj;
        final Object[] args;
        final Object result;
        int proceedCount = 0;
        Object[] lastProceedArgs;

        FakeChain(Executable exe, Object thisObj, Object[] args, Object result) {
            this.exe = exe; this.thisObj = thisObj; this.args = args; this.result = result;
        }
        public Executable getExecutable() { return exe; }
        public Object getThisObject() { return thisObj; }
        public List<Object> getArgs() {
            return Collections.unmodifiableList(new ArrayList<Object>(Arrays.asList(args)));
        }
        public Object getArg(int i) { return getArgs().get(i); }
        public Object proceed() { return proceed(args); }
        public Object proceed(Object[] a) {
            proceedCount++;
            lastProceedArgs = a;
            return result;
        }
        public Object proceedWith(Object r) { throw new UnsupportedOperationException(); }
        public Object proceedWith(Object t, Object[] a) { throw new UnsupportedOperationException(); }
    }

    // ── 伪造框架（复用阶段2模式） ──────────────────────────────────────
    static int installCount = 0;

    static class FakeBuilder implements XposedInterface.HookBuilder {
        public XposedInterface.HookBuilder setPriority(int p) { return this; }
        public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode m) { return this; }
        public XposedInterface.HookBuilder setId(String id) { return this; }
        public XposedInterface.HookHandle intercept(XposedInterface.Hooker h) {
            installCount++;
            return new FakeHandle();
        }
    }

    static class FakeHandle implements XposedInterface.HookHandle {
        public Executable getExecutable() { return null; }
        public void unhook() { }
        public String getId() { return null; }
        public XposedInterface.HookHandle replaceHook(XposedInterface.Hooker h) { return this; }
    }

    static class FakeXposed implements XposedInterface {
        public int getApiVersion() { return API_102; }
        public String getFrameworkName() { return "fake"; }
        public String getFrameworkVersion() { return "0.0"; }
        public long getFrameworkVersionCode() { return 0L; }
        public long getFrameworkProperties() { return 0L; }
        public XposedInterface.HookBuilder hook(Executable target) { return new FakeBuilder(); }
        public XposedInterface.HookBuilder hookClassInitializer(Class<?> c) { throw new UnsupportedOperationException(); }
        public boolean deoptimize(Executable e) { return false; }
        public XposedInterface.Invoker<?, Method> getInvoker(Method m) { throw new UnsupportedOperationException(); }
        public <T> XposedInterface.CtorInvoker<T> getInvoker(Constructor<T> c) { throw new UnsupportedOperationException(); }
        public void log(int priority, String tag, String msg) { }
        public void log(int priority, String tag, String msg, Throwable t) { }
        public android.content.pm.ApplicationInfo getModuleApplicationInfo() { return null; }
        public android.content.SharedPreferences getRemotePreferences(String name) { throw new UnsupportedOperationException(); }
        public String[] listRemoteFiles() { throw new UnsupportedOperationException(); }
        public android.os.ParcelFileDescriptor openRemoteFile(String name) throws java.io.FileNotFoundException {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeModule extends XposedModule { }

    // ── MapperHook 夹具：模仿 Wire pb 的 getter/setter 形状 ─────────────
    public static class FakeContent {
        String text;
        FakeContent(String t) { text = t; }
        public String getText() { return text; }
    }

    public static class FakeMessage {
        Object content;
        Object status;
        boolean contentSet;
        boolean statusSet;
        public Object getId() { return "111"; }
        public Object getContent() { return content; }
        public void setMessageContent(Object c) { contentSet = true; this.content = c; }
        public void setStatus(Object s) { statusSet = true; this.status = s; }
    }

    public static class FakeMsgItem {
        FakeMessage msg;
        FakeMsgItem(FakeMessage m) { msg = m; }
        public Object getMessage() { return msg; }
    }

    public static class FakeChannel {
        public String toString() { return "Channel{id=123456789, name=chat}"; }
    }

    // ── v2.4 Kn 签名发现 + 截获重放夹具 ──
    public static class FakeCallback { }
    public static class FakeOtherCb { }
    public static class FakeUnusedCb { }

    public static class FakeImpl {
        public void reportRead(FakeMPkt pkt, FakeCallback cb) { }          // 唯一形状命中
        public void decoyArity(FakeMPkt pkt) { }                            // 参数个数不符
        public void decoyCb(FakeMPkt pkt, FakeOtherCb cb) { }               // 回调类型不符
        public static void decoyStatic(FakeMPkt pkt, FakeCallback cb) { }   // static 排除
        public Object decoyReturn(FakeMPkt pkt, FakeCallback cb) { return null; } // 返回类型不符
    }

    public static class FakeAmbigImpl {
        public void knA(FakeMPkt pkt, FakeCallback cb) { }
        public void knB(FakeMPkt pkt, FakeCallback cb) { }
    }

    public static class FakeMPkt {
        final java.util.List<String> ids = new ArrayList<String>();
        final java.util.List<String> folds = new ArrayList<String>(Arrays.asList("foldX"));
        public FakeMPkt(String... items) { ids.addAll(Arrays.asList(items)); }
        public java.util.List<String> idsList() { return ids; }     // 与 foldList 同为 List → 考验样本关联
        public java.util.List<String> foldList() { return folds; }
    }

    static void check(boolean cond, String what) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (!cond) failures++;
    }

    static Object[] argsOf(FakeChain c) { return c.lastProceedArgs; }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Throwable {
        // 前置：绑定伪造框架（ModuleLog 经 FakeXposed 落地，alog 走 nativeLog 失败回退后吞掉）
        XposedInterface fake = new FakeXposed();
        FakeModule module = new FakeModule();
        module.attachFramework(fake, new Runnable() { public void run() { } });
        check(ModuleRuntime.bind(module, "p4.test.process"), "ModuleRuntime.bind(伪造模块)");
        ModuleRuntime.setTargetPackage("com.ss.android.lark",
                new URLClassLoader(new URL[0], null));

        Method readReqCtor = FakeChain.class.getDeclaredMethods()[0]; // 仅占位，executable 语义不参与断言

        // ── 1. ReadReqHook：浏览路径（无发送窗口）清空 message_ids/fold_ids 并暂存 ──
        AntiRecall.READ_WINDOW = 0;   // 窗口关闭 = 纯浏览
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        java.util.List<String> ids = new ArrayList<String>(Arrays.asList("a", "b"));
        java.util.List<Long> folds = new ArrayList<Long>(Arrays.asList(7L));
        Object[] ctorArgs = new Object[]{ ids, new FakeChannel(), Long.valueOf(5), null, null, null, null, folds };
        AntiRecall.ReadReqHook readHook = new AntiRecall.ReadReqHook();
        FakeChain c1 = new FakeChain(readReqCtor, null, ctorArgs, null);
        readHook.intercept(c1);
        check(c1.proceedCount == 1, "浏览路径：构造器仍执行一次（proceed）");
        Object[] a1 = argsOf(c1);
        check(a1[0] instanceof List && ((List<Object>) a1[0]).isEmpty(),
                "浏览路径：message_ids 已清空（proceed 收到空表）");
        check(a1[7] instanceof List && ((List<Object>) a1[7]).isEmpty(),
                "浏览路径：fold_ids 已清空");
        LinkedHashSet<String> stash = AntiRecall.PENDING_READ.get("123456789");
        check(stash != null && stash.size() == 2 && stash.contains("a") && stash.contains("b"),
                "浏览路径：原 message_ids 按会话暂存（123456789）");
        check(ids.size() == 2, "浏览路径：调用方原 List 不被原地清空（modern 走副本改参）");

        // ── 2. ReadReqHook：回复窗口路径合并回填暂存 ids ──
        AntiRecall.SendReqHook sendHook = new AntiRecall.SendReqHook();
        FakeChain cs = new FakeChain(readReqCtor, null, new Object[]{ "msg" }, null);
        sendHook.intercept(cs);
        check(System.currentTimeMillis() < AntiRecall.READ_WINDOW,
                "SendReqHook：拦截发送请求构造后 READ_WINDOW 已开窗");
        check(cs.proceedCount == 1, "SendReqHook：构造器仍执行一次");
        java.util.List<String> cur = new ArrayList<String>(Arrays.asList("c"));
        Object[] ctorArgs2 = new Object[]{ cur, new FakeChannel(), Long.valueOf(6), null, null, null, null, folds };
        FakeChain c2 = new FakeChain(readReqCtor, null, ctorArgs2, null);
        readHook.intercept(c2);
        Object[] a2 = argsOf(c2);
        check(a2[0] instanceof List && ((List<Object>) a2[0]).containsAll(Arrays.asList("a", "b", "c"))
                        && ((List<Object>) a2[0]).size() == 3,
                "回复窗口：暂存 ids(a,b) 与本次(c) 合并回填");
        check(((List<?>) a2[7]) == folds, "回复窗口：fold_ids 不动（仅浏览路径清空）");
        check(AntiRecall.PENDING_READ.get("123456789") == null,
                "回复窗口：暂存表该会话条目已消费（remove）");

        // ── 3. ReadReqHook：开关关 = 原参透传 ──
        Config.antiread = false;
        Object[] ctorArgs3 = new Object[]{ ids, new FakeChannel(), Long.valueOf(5), null, null, null, null, folds };
        FakeChain c3 = new FakeChain(readReqCtor, null, ctorArgs3, null);
        readHook.intercept(c3);
        Object[] a3 = argsOf(c3);
        check(a3[0] == ids && a3[7] == folds,
                "开关关：message_ids/fold_ids 原样透传给 proceed");

        // ── 4. ReadReqHook：短参数构造安全放行 ──
        Object[] shortArgs = new Object[]{ ids };
        FakeChain c4 = new FakeChain(readReqCtor, null, shortArgs, null);
        readHook.intercept(c4);
        check(c4.proceedCount == 1 && argsOf(c4).length == 1,
                "短参数(<8)构造：不抛异常、原参放行");

        // ── 5. InvokeHook：现状 ANTIREAD_DROP=false 只记录不短路 ──
        AntiRecall.InvokeHook invokeHook = new AntiRecall.InvokeHook();
        FakeChain c5 = new FakeChain(readReqCtor, null, new Object[]{ Integer.valueOf(1021) }, "RESULT");
        Object r5 = invokeHook.intercept(c5);
        check(c5.proceedCount == 1 && "RESULT".equals(r5),
                "InvokeHook(1021)：原调用执行且返回值透传（不短路）");
        FakeChain c5b = new FakeChain(readReqCtor, null, new Object[]{ Integer.valueOf(10000) }, "R");
        invokeHook.intercept(c5b);
        check(c5b.proceedCount == 1, "InvokeHook(10000 噪音)：仍放行原调用");

        // ── 6. MapperHook：文本入缓存；空文本回填缓存 + 恢复状态 ──
        AntiRecall.CACHE.clear();
        Object normal = new Object();   // Message$Status.NORMAL 替身
        AntiRecall.MapperHook mapper = new AntiRecall.MapperHook(normal);
        FakeMessage m1 = new FakeMessage();
        m1.content = new FakeContent("hello");
        FakeChain c6 = new FakeChain(readReqCtor, null, new Object[]{ new FakeMsgItem(m1) }, null);
        mapper.intercept(c6);
        check(AntiRecall.CACHE.get("111") == m1.content && c6.proceedCount == 1,
                "MapperHook：有文本 -> 入缓存且原方法执行");
        FakeMessage m2 = new FakeMessage();
        m2.content = new FakeContent(null);
        AntiRecall.CACHE.put("111", m1.content);   // 确保缓存命中
        FakeChain c6b = new FakeChain(readReqCtor, null, new Object[]{ new FakeMsgItem(m2) }, null);
        mapper.intercept(c6b);
        check(m2.contentSet && m2.content == m1.content && m2.statusSet && m2.status == normal,
                "MapperHook：空文本 -> 回填缓存内容 + setStatus(NORMAL)");
        check(c6b.proceedCount == 1, "MapperHook：原方法仍执行");

        // ── 7. RestrictedModeUnlock 共享门禁回调 ──
        Config.restrictunlock = true;
        FakeChain c7 = new FakeChain(readReqCtor, null, new Object[]{}, "BOOL");
        Object r7 = RestrictedModeUnlock.FALSE_GATE.intercept(c7);
        check(Boolean.FALSE.equals(r7) && c7.proceedCount == 0,
                "FALSE_GATE 开关开：返回 false 且不执行原方法（setResult(false) 等价）");
        FakeChain c7b = new FakeChain(readReqCtor, null, new Object[]{}, null);
        Object r7b = RestrictedModeUnlock.VOID_GATE_RESTRICT.intercept(c7b);
        check(r7b == null && c7b.proceedCount == 0,
                "VOID_GATE_RESTRICT 开关开：返回 null 且不执行原方法（setResult(null) 等价）");
        Config.restrictunlock = false;
        FakeChain c7c = new FakeChain(readReqCtor, null, new Object[]{}, "GO");
        Object r7c = RestrictedModeUnlock.FALSE_GATE.intercept(c7c);
        check("GO".equals(r7c) && c7c.proceedCount == 1,
                "FALSE_GATE 开关关：proceed 放行且返回值透传");
        Config.noauditall = true;
        FakeChain c7d = new FakeChain(readReqCtor, null, new Object[]{}, null);
        Object r7d = RestrictedModeUnlock.VOID_GATE_NOAUDIT.intercept(c7d);
        check(r7d == null && c7d.proceedCount == 0, "VOID_GATE_NOAUDIT 开关开：审计上报短路");
        Config.noauditall = false;

        // ── 8. 注册面：HookRuntime 幂等（同 logicalId 二次安装 0 新框架安装） ──
        XposedInterface.Hooker noop = new XposedInterface.Hooker() {
            public Object intercept(XposedInterface.Chain chain) throws Throwable { return chain.proceed(); }
        };
        int before = installCount;
        HookRuntime.InstalledHook first =
                HookRuntime.hookMethod(FakeChannel.class, "toString", new Class<?>[0], "p4/e2e/toString", noop);
        check(first != null && installCount - before == 1, "HookRuntime.hookMethod 首次安装");
        HookRuntime.InstalledHook dup =
                HookRuntime.hookMethod(FakeChannel.class, "toString", new Class<?>[0], "p4/e2e/toString", noop);
        check(dup == first && installCount - before == 1, "同 logicalId 二次安装幂等（0 新框架安装）");

        // ── 9. v2.4 签名发现：Kn 按形状唯一定位, 多义/干扰项全部排除 ──
        Object[] kn = AntiRecall.discoverKn(FakeImpl.class, FakeCallback.class);
        check(kn != null && "reportRead".equals(((java.lang.reflect.Method) kn[0]).getName())
                        && kn[1] == FakeMPkt.class,
                "签名发现：Kn 唯一命中(过滤参数个数/回调类型/static/返回类型干扰项)");
        check(AntiRecall.discoverKn(FakeAmbigImpl.class, FakeCallback.class) == null,
                "签名发现：双命中 → 保守返回 null(降级纯载体)");
        check(AntiRecall.discoverKn(FakeImpl.class, FakeUnusedCb.class) == null,
                "签名发现：回调类型无任何方法使用 → null");

        // ── 10. v2.4 截获落账：Kn 线程槽 + 读请求构造按会话落账(KN_IDS 累加/KN_CTX 模板/样本) ──
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        AntiRecall.KN_READY = true;
        AntiRecall.KN_CTX.clear();
        AntiRecall.KN_IDS.clear();
        AntiRecall.knAccTried = false;
        AntiRecall.knAccIds = null;
        AntiRecall.KnCaptureHook knCap = new AntiRecall.KnCaptureHook();
        Object implObj = new FakeImpl(), cbObj = new FakeCallback();
        AntiRecall.READ_WINDOW = 0;   // 强制浏览分支(§2 开的 2.5s 窗口可能仍在)
        FakeMPkt pkt1 = new FakeMPkt("m1", "m2");
        knCap.intercept(new FakeChain(readReqCtor, implObj, new Object[]{ pkt1, cbObj }, null));
        check(AntiRecall.CURRENT_KN.get() != null && AntiRecall.KN_CTX.isEmpty(),
                "Kn截获：入口只落线程槽(不调用任何混淆访问器)");
        java.util.List<String> ids1 = pkt1.idsList();   // 同一引用进构造参数(builder 直传)
        FakeChain c10a = new FakeChain(readReqCtor, null,
                new Object[]{ ids1, new FakeChannel(), Long.valueOf(9), null, null, null, null, folds }, null);
        readHook.intercept(c10a);
        check(AntiRecall.CURRENT_KN.get() == null,
                "Kn截获：读请求构造落账后线程槽已消费");
        check(AntiRecall.KN_CTX.get("123456789") != null
                        && AntiRecall.KN_CTX.get("123456789")[0] == implObj
                        && AntiRecall.KN_CTX.get("123456789")[1] == pkt1
                        && AntiRecall.KN_CTX.get("123456789")[3] == ids1,
                "Kn截获：按会话落账 {impl,m,cb,ids样本}(样本=构造参数原列表引用)");
        LinkedHashSet<String> acc = AntiRecall.KN_IDS.get("123456789");
        check(acc != null && acc.size() == 2 && acc.containsAll(Arrays.asList("m1", "m2")),
                "Kn截获：ids 按会话累加(第1包 2条)");
        // 多包拆分: 第2包(模板刷新+累加不丢)
        FakeMPkt pkt2 = new FakeMPkt("m3");
        knCap.intercept(new FakeChain(readReqCtor, implObj, new Object[]{ pkt2, cbObj }, null));
        java.util.List<String> ids2 = pkt2.idsList();
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ ids2, new FakeChannel(), Long.valueOf(10), null, null, null, null, folds }, null));
        check(AntiRecall.KN_IDS.get("123456789").size() == 3
                        && AntiRecall.KN_IDS.get("123456789").containsAll(Arrays.asList("m1", "m2", "m3"))
                        && AntiRecall.KN_CTX.get("123456789")[1] == pkt2
                        && AntiRecall.KN_CTX.get("123456789")[3] == ids2,
                "Kn截获：多包拆分累加不丢(3条) 且模板/样本刷新为最新包");

        // ── 11. v2.4 重放：样本关联定位 ids 访问器 → 模板列表原地替换为累加全集 → 重放并清缓存 ──
        final int[] replayed = new int[1];
        final Object[] repArgs = new Object[3];
        final boolean[] fail = new boolean[1];
        AntiRecall.sReplayer = new AntiRecall.KnReplayer() {
            public void replay(Object impl, Object m, Object cb) {
                replayed[0]++; repArgs[0] = impl; repArgs[1] = m; repArgs[2] = cb;
                if (fail[0]) throw new RuntimeException("boom");
            }
        };
        FakeChain c9 = new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null);
        sendHook.intercept(c9);
        check(replayed[0] == 1 && repArgs[0] == implObj && repArgs[1] == pkt2 && repArgs[2] == cbObj,
                "重放：回复瞬间触发一次且三元组传递(模板=最新包)");
        check(AntiRecall.knAccIds != null
                        && ((FakeMPkt) repArgs[1]).idsList().size() == 3
                        && ((FakeMPkt) repArgs[1]).idsList().containsAll(Arrays.asList("m1", "m2", "m3"))
                        && ((FakeMPkt) repArgs[1]).foldList().equals(Arrays.asList("foldX")),
                "重放：样本关联定位 ids 访问器(区分双 List) 且原地替换为累加全集、fold 不动");
        check(AntiRecall.KN_CTX.get("123456789") == null && AntiRecall.KN_IDS.get("123456789") == null,
                "重放：成功后该会话 ctx/ids 已清");
        check(c9.proceedCount == 1, "重放：回复构造器仍执行一次");
        check(!Boolean.TRUE.equals(AntiRecall.KN_REPLAY.get()), "重放：卫兵已复位");
        // 载体兜底幂等：重放后窗口内载体再到来 -> merged 只剩载体自身 ids，不重复
        java.util.List<String> carrier = new ArrayList<String>(Arrays.asList("c"));
        FakeChain c9b = new FakeChain(readReqCtor, null,
                new Object[]{ carrier, new FakeChannel(), Long.valueOf(6), null, null, null, null, folds }, null);
        readHook.intercept(c9b);
        Object[] a9b = argsOf(c9b);
        check(((List<?>) a9b[0]).equals(Arrays.asList("c")),
                "重放后载体兜底：缓存已清，仅放行载体自身 ids（幂等）");

        // ── 12. 重放失败：缓存保留，可重试 ──
        fail[0] = true;
        AntiRecall.READ_WINDOW = 0;   // §11 重开了窗口, 落账需要浏览分支
        knCap.intercept(new FakeChain(readReqCtor, implObj, new Object[]{ new FakeMPkt("x"), cbObj }, null));
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ new FakeMPkt("x").idsList(), new FakeChannel(), Long.valueOf(11), null, null, null, null, folds }, null));
        FakeChain c10 = new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null);
        sendHook.intercept(c10);
        check(replayed[0] == 2 && AntiRecall.KN_CTX.get("123456789") != null
                        && AntiRecall.KN_IDS.get("123456789") != null,
                "重放失败：异常被吞且缓存保留（走载体兜底/下次回复重试）");
        fail[0] = false;
        sendHook.intercept(c10);
        check(replayed[0] == 3 && AntiRecall.KN_CTX.get("123456789") == null,
                "重放重试：下次回复成功后清缓存");

        // ── 13. 会话隔离：回 B 不重放 A ──
        AntiRecall.READ_WINDOW = 0;
        FakeMPkt aPkt = new FakeMPkt("aOnly");
        Object implA = new FakeImpl();
        knCap.intercept(new FakeChain(readReqCtor, implA, new Object[]{ aPkt, cbObj }, null));
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ aPkt.idsList(), new FakeChannel(), Long.valueOf(12), null, null, null, null, folds }, null));
        FakeChain c11 = new FakeChain(readReqCtor, null, new Object[]{ "22222222" }, null);
        sendHook.intercept(c11);
        check(replayed[0] == 3 && AntiRecall.KN_CTX.get("123456789") != null
                        && AntiRecall.KN_CTX.get("123456789")[1] == aPkt,
                "会话隔离：回复 B(22222222) 不触发 A(123456789) 的重放，A 缓存原样");

        // ── 14. 开关关 / 通道未就绪：完全不触发 ──
        Config.antiread = false;
        sendHook.intercept(c11);
        check(replayed[0] == 3 && AntiRecall.KN_CTX.get("123456789") != null,
                "开关关：不重放且缓存不动");
        AntiRecall.KN_READY = false;
        Config.antiread = true;
        sendHook.intercept(c11);
        check(replayed[0] == 3 && AntiRecall.KN_CTX.get("123456789") != null,
                "通道未就绪(KN_READY=false)：不重放（纯载体模式）");
        AntiRecall.KN_READY = true;

        // ── 15. KN_REPLAY 卫兵：重放期间浏览抑制、Kn 截获、落账全部直通 ──
        AntiRecall.READ_WINDOW = 0;   // 强制浏览分支（若无卫兵会清空 ids）
        AntiRecall.PENDING_READ.remove("123456789");
        AntiRecall.KN_REPLAY.set(Boolean.TRUE);
        java.util.List<String> selfIds = new ArrayList<String>(Arrays.asList("self1"));
        FakeChain c13 = new FakeChain(readReqCtor, null,
                new Object[]{ selfIds, new FakeChannel(), Long.valueOf(1), null, null, null, null, folds }, null);
        readHook.intercept(c13);
        Object[] a13 = argsOf(c13);
        check(a13[0] == selfIds && ((List<?>) a13[0]).size() == 1
                        && AntiRecall.PENDING_READ.get("123456789") == null,
                "卫兵直通：重放期间读请求 hook 不做任何改参/暂存");
        FakeMPkt replayPkt = new FakeMPkt("rp1");
        knCap.intercept(new FakeChain(readReqCtor, implA, new Object[]{ replayPkt, cbObj }, null));
        readHook.intercept(new FakeChain(readReqCtor, null,
                new Object[]{ replayPkt.idsList(), new FakeChannel(), Long.valueOf(13), null, null, null, null, folds }, null));
        check(AntiRecall.KN_IDS.get("123456789").contains("aOnly")
                        && !AntiRecall.KN_IDS.get("123456789").contains("rp1")
                        && AntiRecall.CURRENT_KN.get() == null,
                "卫兵直通：重放期间 Kn 截获与落账全部不生效");
        AntiRecall.KN_REPLAY.set(Boolean.FALSE);
        AntiRecall.CURRENT_KN.remove();
        readHook.intercept(c13);
        Object[] a13b = argsOf(c13);
        check(((List<?>) a13b[0]).isEmpty()
                        && AntiRecall.PENDING_READ.get("123456789").contains("self1"),
                "卫兵复位后：同一请求恢复浏览抑制语义（清空+暂存）");
        AntiRecall.KN_READY = false;

        // ── 收尾：还原全局状态，避免影响同 JVM 其它用例 ──
        Config.antiread = false;
        AntiRecall.READ_WINDOW = 0;
        AntiRecall.CACHE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.KN_CTX.clear();
        AntiRecall.KN_IDS.clear();
        AntiRecall.knAccTried = false;
        AntiRecall.knAccIds = null;
        AntiRecall.sReplayer = new AntiRecall.DefaultKnReplayer();

        System.out.println(failures == 0
                ? "== PASS：全部 hook 迁移行为断言通过 =="
                : "== FAIL：" + failures + " 条断言未过 ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
