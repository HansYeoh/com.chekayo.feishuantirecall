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
 * 9~13. v2.1 回复即主动补报（feat/read-as-unread）：回复瞬间携带暂存 ids 触发一次、
 *    成功清暂存、失败保留可重试、按会话隔离、开关关/通道未就绪不触发、
 *    SYNTH_ING 卫兵直通（自构造请求不被当浏览清空）、补报后载体兜底路径幂等。
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
        LinkedHashSet<String> stash = AntiRecall.PENDING_READ.get("123456789").ids;
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

        // ── 9. v2.1 主动补报：回复瞬间用暂存参数构造读上报直发（不依赖载体） ──
        Config.antiread = true;
        AntiRecall.TAMPER = 0;
        AntiRecall.SYNTH_READY = true;
        AntiRecall.PENDING_READ.clear();
        final int[] reported = new int[1];
        final String[] repCh = new String[1];
        final java.util.List<LinkedHashSet<String>> repIds = new ArrayList<LinkedHashSet<String>>();
        final boolean[] fail = new boolean[1];
        AntiRecall.sReporter = new AntiRecall.ReadReporter() {
            public void report(String ch, AntiRecall.ReadStash st) {
                reported[0]++; repCh[0] = ch;
                LinkedHashSet<String> copy = new LinkedHashSet<String>(st.ids);
                repIds.add(copy);
                if (fail[0]) throw new RuntimeException("boom");
            }
        };
        AntiRecall.ReadStash st9 = new AntiRecall.ReadStash();
        st9.ids.add("m1"); st9.ids.add("m2");
        st9.channel = new FakeChannel(); st9.maxPosition = Long.valueOf(9); st9.foldIds = folds;
        AntiRecall.PENDING_READ.put("123456789", st9);
        FakeChain c9 = new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null);
        sendHook.intercept(c9);
        check(reported[0] == 1 && "123456789".equals(repCh[0])
                        && repIds.get(0).containsAll(Arrays.asList("m1", "m2")),
                "主动补报：回复瞬间触发一次且携带暂存 ids");
        check(AntiRecall.PENDING_READ.get("123456789") == null,
                "主动补报：成功后该会话暂存已清");
        check(c9.proceedCount == 1, "主动补报：回复构造器仍执行一次");
        check(!Boolean.TRUE.equals(AntiRecall.SYNTH_ING.get()), "主动补报：卫兵已复位");
        // 载体兜底路径幂等：补报后窗口内载体再到来 -> merged 只剩载体自身 ids，不重复
        java.util.List<String> carrier = new ArrayList<String>(Arrays.asList("c"));
        FakeChain c9b = new FakeChain(readReqCtor, null,
                new Object[]{ carrier, new FakeChannel(), Long.valueOf(6), null, null, null, null, folds }, null);
        readHook.intercept(c9b);
        Object[] a9b = argsOf(c9b);
        check(((List<?>) a9b[0]).equals(Arrays.asList("c")),
                "补报后载体兜底：暂存已清，仅放行载体自身 ids（幂等）");

        // ── 10. 主动补报失败：暂存保留，可重试 ──
        fail[0] = true;
        AntiRecall.ReadStash st10 = new AntiRecall.ReadStash();
        st10.ids.add("x");
        AntiRecall.PENDING_READ.put("123456789", st10);
        FakeChain c10 = new FakeChain(readReqCtor, null, new Object[]{ "123456789" }, null);
        sendHook.intercept(c10);
        check(reported[0] == 2 && AntiRecall.PENDING_READ.get("123456789") != null,
                "补报失败：异常被吞且暂存保留（走载体兜底/下次回复重试）");
        fail[0] = false;
        sendHook.intercept(c10);
        check(reported[0] == 3 && AntiRecall.PENDING_READ.get("123456789") == null,
                "补报重试：下次回复成功后清暂存");

        // ── 11. 主动补报按会话隔离：回 B 不补 A ──
        AntiRecall.ReadStash st11 = new AntiRecall.ReadStash();
        st11.ids.add("aOnly");
        AntiRecall.PENDING_READ.put("11111111", st11);
        FakeChain c11 = new FakeChain(readReqCtor, null, new Object[]{ "22222222" }, null);
        sendHook.intercept(c11);
        check(reported[0] == 3 && AntiRecall.PENDING_READ.get("11111111") != null,
                "会话隔离：回复 B(22222222) 不触发 A(11111111) 的补报，A 暂存原样");

        // ── 12. 主动补报开关关 / 通道未就绪：完全不触发 ──
        Config.antiread = false;
        FakeChain c12 = new FakeChain(readReqCtor, null, new Object[]{ "11111111" }, null);
        sendHook.intercept(c12);
        check(reported[0] == 3 && AntiRecall.PENDING_READ.get("11111111") != null,
                "开关关：不补报且暂存不动");
        AntiRecall.SYNTH_READY = false;
        Config.antiread = true;
        sendHook.intercept(c12);
        check(reported[0] == 3 && AntiRecall.PENDING_READ.get("11111111") != null,
                "通道未就绪(SYNTH_READY=false)：不补报（纯载体模式）");
        AntiRecall.SYNTH_READY = true;

        // ── 13. 卫兵直通：自构造补报重入读请求 hook 时原样放行，不被当浏览清空 ──
        AntiRecall.READ_WINDOW = 0;   // 强制浏览分支（若无卫兵会清空 ids）
        AntiRecall.SYNTH_ING.set(Boolean.TRUE);
        java.util.List<String> selfIds = new ArrayList<String>(Arrays.asList("self1"));
        FakeChain c13 = new FakeChain(readReqCtor, null,
                new Object[]{ selfIds, new FakeChannel(), Long.valueOf(1), null, null, null, null, folds }, null);
        readHook.intercept(c13);
        Object[] a13 = argsOf(c13);
        check(a13[0] == selfIds && ((List<?>) a13[0]).size() == 1
                        && AntiRecall.PENDING_READ.get("123456789") == null,
                "卫兵直通：SYNTH_ING 置位时读请求 hook 不做任何改参/暂存");
        AntiRecall.SYNTH_ING.set(Boolean.FALSE);
        readHook.intercept(c13);
        Object[] a13b = argsOf(c13);
        check(((List<?>) a13b[0]).isEmpty()
                        && AntiRecall.PENDING_READ.get("123456789").ids.contains("self1"),
                "卫兵复位后：同一请求恢复浏览抑制语义（清空+暂存）");
        AntiRecall.SYNTH_READY = false;

        // ── 收尾：还原全局状态，避免影响同 JVM 其它用例 ──
        Config.antiread = false;
        AntiRecall.READ_WINDOW = 0;
        AntiRecall.CACHE.clear();
        AntiRecall.PENDING_READ.clear();
        AntiRecall.sReporter = new AntiRecall.DefaultReadReporter();

        System.out.println(failures == 0
                ? "== PASS：全部 hook 迁移行为断言通过 =="
                : "== FAIL：" + failures + " 条断言未过 ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
