package com.chekayo.feishuantirecall;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 阶段 5 行为验证：hot reload 安全门控 + 新一代入口接线（宿主 JVM 直跑，06 文档）。
 * 覆盖：§2 四类资源门控（native/线程/外部回调/已装 Java hook 任一存在即拒绝，全干净才放行；
 * 第四类为 2026-10 审计 F2 修复——启动窗口内「已装 hook、配置桥接收器未注册」曾被误判干净）、
 * §3 状态表语义（闩幂等、延时任务计数到 0 归零、重复 end 不为负）、
 * §4 新一代入口（bind 接线 + 旧 handle 全量 unhook + ModulePath 补路径 + 不做业务分发）、
 * bind 失败 fail-closed（仅清旧 handle）、reload 后新加载的包照常完整分发。
 * 产物不进 APK、不依赖真机；真机 reload 协商回归按计划属阶段 7。
 */
public class HotReloadBehaviorTest {

    static int failures = 0;
    static final List<String> LOGS = new ArrayList<String>();

    // ── 伪造框架（接口直实现；XposedModule 构造后 attachFramework） ──

    static class FakeXposed implements XposedInterface {
        public int getApiVersion() { return API_102; }
        public String getFrameworkName() { return "fake"; }
        public String getFrameworkVersion() { return "0.0"; }
        public long getFrameworkVersionCode() { return 0L; }
        public long getFrameworkProperties() { return 0L; }
        public XposedInterface.HookBuilder hook(java.lang.reflect.Executable target) {
            throw new UnsupportedOperationException("门控测试不安装 hook");
        }
        public XposedInterface.HookBuilder hookClassInitializer(Class<?> c) { throw new UnsupportedOperationException(); }
        public boolean deoptimize(java.lang.reflect.Executable e) { return false; }
        public XposedInterface.Invoker<?, java.lang.reflect.Method> getInvoker(java.lang.reflect.Method m) { throw new UnsupportedOperationException(); }
        public <T> XposedInterface.CtorInvoker<T> getInvoker(java.lang.reflect.Constructor<T> c) { throw new UnsupportedOperationException(); }
        public void log(int priority, String tag, String msg) {
            synchronized (LOGS) { LOGS.add(tag + "|" + msg); }
        }
        public void log(int priority, String tag, String msg, Throwable t) {
            synchronized (LOGS) { LOGS.add(tag + "|" + msg + "|" + t); }
        }
        public android.content.pm.ApplicationInfo getModuleApplicationInfo() { return null; }
        public android.content.SharedPreferences getRemotePreferences(String name) { throw new UnsupportedOperationException(); }
        public String[] listRemoteFiles() { throw new UnsupportedOperationException(); }
        public android.os.ParcelFileDescriptor openRemoteFile(String name) throws java.io.FileNotFoundException {
            throw new UnsupportedOperationException();
        }
    }

    static class FakeModuleLoadedParam implements ModuleLoadedParam {
        public boolean isSystemServer() { return false; }
        public String getProcessName() { return "main"; }
    }

    static class FakePackageParam implements PackageReadyParam {
        final String pkg; final ClassLoader cl;
        FakePackageParam(String pkg, ClassLoader cl) { this.pkg = pkg; this.cl = cl; }
        public String getPackageName() { return pkg; }
        public android.content.pm.ApplicationInfo getApplicationInfo() { return null; }
        public boolean isFirstPackage() { return true; }
        public ClassLoader getDefaultClassLoader() { return cl; }
        public ClassLoader getClassLoader() { return cl; }
        public android.app.AppComponentFactory getAppComponentFactory() { return null; }
    }

    /** 记录 setSavedInstanceState 的协商参数（旧代侧）。 */
    static class RecordingReloadingParam implements HotReloadingParam {
        Object saved;
        int setCount;
        public android.os.Bundle getExtras() { return null; }
        public void setSavedInstanceState(Object o) { this.saved = o; this.setCount++; }
    }

    /** 记录 unhook 次数的旧 handle（新代侧）。 */
    static class FakeHandle implements XposedInterface.HookHandle {
        final String id; int unhookCount;
        FakeHandle(String id) { this.id = id; }
        public java.lang.reflect.Executable getExecutable() { return null; }
        public void unhook() { unhookCount++; }
        public String getId() { return id; }
        public XposedInterface.HookHandle replaceHook(XposedInterface.Hooker h) { return this; }
    }

    /** 支持真实安装的伪造框架：记录安装次数（审计 F2 断言用，其余行为同 FakeXposed）。 */
    static class HookingXposed extends FakeXposed {
        int installed = 0;
        public XposedInterface.HookBuilder hook(final java.lang.reflect.Executable target) {
            return new XposedInterface.HookBuilder() {
                public XposedInterface.HookBuilder setPriority(int p) { return this; }
                public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode m) { return this; }
                public XposedInterface.HookBuilder setId(String id) { return this; }
                public XposedInterface.HookHandle intercept(XposedInterface.Hooker h) {
                    installed++;
                    return new FakeHandle("hook#" + installed);
                }
            };
        }
    }

    static class FakeReloadedParam implements HotReloadedParam {
        final String process;
        final List<XposedInterface.HookHandle> handles;
        final Object saved;
        FakeReloadedParam(String process, List<XposedInterface.HookHandle> handles, Object saved) {
            this.process = process; this.handles = handles; this.saved = saved;
        }
        public boolean isSystemServer() { return false; }
        public String getProcessName() { return process; }
        public android.os.Bundle getExtras() { return null; }
        public Object getSavedInstanceState() { return saved; }
        public List<XposedInterface.HookHandle> getOldHookHandles() { return handles; }
    }

    static FeishuKitModule newModule() {
        FeishuKitModule m = new FeishuKitModule();
        m.attachFramework(new FakeXposed(), new Runnable() { public void run() { } });
        return m;
    }

    static void check(boolean cond, String what) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (!cond) failures++;
    }

    static int logCount(String needle) {
        synchronized (LOGS) {
            int n = 0;
            for (String l : LOGS) if (l.contains(needle)) n++;
            return n;
        }
    }

    /** 模拟进入新模块代：真机上新代由新 ClassLoader 加载，static 全新；测试在同一 loader 里清表等效。 */
    static void resetRuntimeForNewGeneration() throws Exception {
        for (String f : new String[]{"sModule", "sGenerationId", "sProcessName",
                "sModuleApkPath", "sPackageName", "sPackageClassLoader"}) {
            Field fd = ModuleRuntime.class.getDeclaredField(f);
            fd.setAccessible(true);
            fd.set(null, null);
        }
        HotReloadSafety.resetForTest();
    }

    static List<XposedInterface.HookHandle> handles(XposedInterface.HookHandle... hs) {
        return new ArrayList<XposedInterface.HookHandle>(java.util.Arrays.asList(hs));
    }

    static final String FEISHU = "com.ss.android.lark";

    public static void main(String[] args) throws Exception {
        AntiRecall.g_lark_mark = null;
        HotReloadSafety.resetForTest();

        // ── 1. 干净状态（06 文档 §2 四类门控全空） ──
        check(!HotReloadSafety.hasNativeHooks(), "干净进程无 native hook 登记");
        check(!HotReloadSafety.hasModuleThreads(), "干净进程无线程/延时任务登记");
        check(!HotReloadSafety.hasExternalCallbacks(), "干净进程无外部回调登记");
        check(!HotReloadSafety.hasJavaHooks(), "干净进程无 Java hook 登记");
        check("(clean)".equals(HotReloadSafety.describe()), "describe 干净态 = (clean)");

        // ── 2. 放行路径：门控全空 → true + classloader-neutral 门控结论（06 文档 §2） ──
        FeishuKitModule m1 = newModule();
        m1.onModuleLoaded(new FakeModuleLoadedParam());
        String gen1 = ModuleRuntime.getGenerationId();
        RecordingReloadingParam accept = new RecordingReloadingParam();
        check(m1.onHotReloading(accept) == true, "无 teardown-unsafe 资源时 onHotReloading 返回 true");
        check("feishukit:generation-clean".equals(accept.saved) && accept.setCount == 1,
                "放行时向新一代传门控结论（且只传一次）");
        check(logCount("hot reload accepted") == 1, "放行已记日志");

        // ── 3. native hook 登记 → 拒绝（06 文档 §2 门控一） ──
        HotReloadSafety.markNativeHook("antirecall.native-inline");
        check(HotReloadSafety.hasNativeHooks() && !HotReloadSafety.hasModuleThreads()
                && !HotReloadSafety.hasExternalCallbacks(), "native 登记只影响门控一");
        RecordingReloadingParam rejectN = new RecordingReloadingParam();
        check(m1.onHotReloading(rejectN) == false, "存在 native hook 时拒绝 reload");
        check(rejectN.saved == null && rejectN.setCount == 0, "拒绝时不向新一代传任何状态");
        check(logCount("hot reload rejected") == 1 && logCount("antirecall.native-inline") >= 1,
                "拒绝日志带 describe 资源清单");

        // ── 4. 线程登记 → 拒绝（06 文档 §2 门控二） ──
        HotReloadSafety.resetForTest();
        HotReloadSafety.markThread("antirecall.installer-thread");
        check(m1.onHotReloading(new RecordingReloadingParam()) == false, "存在模块线程时拒绝 reload");

        // ── 5. 外部回调登记 → 拒绝（06 文档 §2 门控三） ──
        HotReloadSafety.resetForTest();
        HotReloadSafety.markExternalCallback("config-bridge.receiver(sync,pull)");
        check(m1.onHotReloading(new RecordingReloadingParam()) == false, "存在外部回调时拒绝 reload");
        check(HotReloadSafety.hasExternalCallbacks() && !HotReloadSafety.hasNativeHooks(),
                "回调登记只影响门控三");

        // ── 6. 闩语义：同名重复登记幂等（describe 只列一次） ──
        HotReloadSafety.markThread("resigntracker.tracker-thread");
        HotReloadSafety.markThread("resigntracker.tracker-thread");
        String d6 = HotReloadSafety.describe();
        check(d6.contains("threads=[") && d6.indexOf("resigntracker.tracker-thread")
                == d6.lastIndexOf("resigntracker.tracker-thread"),
                "同名线程重复登记在 describe 中只出现一次");
        check(HotReloadSafety.hasModuleThreads(), "登记后有模块线程");

        // ── 7. 延时任务计数：begin 拒绝 / end 归零放行 / 重复 end 不为负（06 文档 §3.2） ──
        HotReloadSafety.resetForTest();
        HotReloadSafety.beginDelayedTask("profilecapture.scrape-delayed");
        HotReloadSafety.beginDelayedTask("profilecapture.scrape-delayed");
        check(HotReloadSafety.hasModuleThreads() && HotReloadSafety.describe().contains("profilecapture.scrape-delayed=2"),
                "两个未完成延时任务按计数登记");
        check(m1.onHotReloading(new RecordingReloadingParam()) == false, "存在未完成延时任务时拒绝 reload");
        HotReloadSafety.endDelayedTask("profilecapture.scrape-delayed");
        check(HotReloadSafety.hasModuleThreads() && HotReloadSafety.describe().contains("=1"),
                "剩一个未完成延时任务仍拒绝语义成立");
        HotReloadSafety.endDelayedTask("profilecapture.scrape-delayed");
        HotReloadSafety.endDelayedTask("profilecapture.scrape-delayed");   // 多余的 end
        check(!HotReloadSafety.hasModuleThreads() && "(clean)".equals(HotReloadSafety.describe()),
                "计数归零后回到干净态（多余 end 不产生负数）");
        check(m1.onHotReloading(new RecordingReloadingParam()) == true, "延时任务全部结束后重新放行");

        // ── 8. 三类并存：拒绝一次列全，重复拒绝稳定不崩溃 ──
        HotReloadSafety.markNativeHook("resigntracker.native-inline");
        HotReloadSafety.markThread("lark-resign-boot");
        HotReloadSafety.markExternalCallback("download-mirror.file-observer");
        RecordingReloadingParam r1 = new RecordingReloadingParam();
        RecordingReloadingParam r2 = new RecordingReloadingParam();
        boolean first = m1.onHotReloading(r1);
        boolean second = m1.onHotReloading(r2);
        check(!first && !second, "三类资源并存时两次协商都拒绝（稳定、无异常）");
        String d8 = HotReloadSafety.describe();
        check(d8.contains("native=[") && d8.contains("threads=[") && d8.contains("callbacks=["),
                "describe 分组列出三类资源: " + d8);
        check(r1.saved == null && r2.saved == null, "拒绝路径不写 savedState");

        // ── 9. 新一代入口（06 文档 §4）：真机新代无 onModuleLoaded，直接进 onHotReloaded ──
        resetRuntimeForNewGeneration();
        FeishuKitModule m2 = newModule();
        FakeHandle h1 = new FakeHandle("antirecall.mapper");
        FakeHandle h2 = new FakeHandle("profilecapture.onCreate");
        m2.onHotReloaded(new FakeReloadedParam("main", handles(h1, h2), "feishukit:generation-clean"));
        check(ModuleRuntime.isBound() && ModuleRuntime.module() == m2, "新代 onHotReloaded 完成运行时绑定");
        String gen2 = ModuleRuntime.getGenerationId();
        check(gen2 != null && !gen2.equals(gen1), "换代后 generation id 更新: " + gen1 + " -> " + gen2);
        check(h1.unhookCount == 1 && h2.unhookCount == 1, "旧 handle 全量且各恰好 unhook 一次（框架默认语义）");
        check(logCount("onHotReloaded: generation ") == 1
                && logCount("savedState=feishukit:generation-clean") == 1
                && logCount("oldHandles=2") == 1, "换代日志记录新代 id/savedState/旧 handle 数");
        check(logCount("old handle antirecall.mapper") == 1 && logCount("old handle profilecapture.onCreate") == 1,
                "逐条记录旧 handle id");
        check(logCount("ModulePath: module apk path unavailable") >= 1, "新代补 ModulePath（fake 无 appInfo → fail-closed 日志）");
        check(logCount("onHotReloaded: new generation ready, safety=(clean)") == 1,
                "新代就绪日志确认安全表为空（新代从零起步）");
        check(logCount("HookRuntime: installed") == 0 && logCount("onPackageReady: dispatch") == 0,
                "换代入口不做业务分发（06 文档 §4.4：只做现代入口初始化）");

        // ── 10. reload 后新加载的包照常走完整分发（reload 不重放旧包回调，但新包回调照常） ──
        m2.onPackageReady(new FakePackageParam(FEISHU, new URLClassLoader(new URL[0], null)));
        check(logCount("onPackageReady: dispatch " + FEISHU) == 1, "新代对 reload 后加载的包正常分发");
        check(FEISHU.equals(ModuleRuntime.getPackageName()), "新代目标包/ClassLoader 已设置");

        // ── 11. bind 失败 fail-closed（06 文档 §4.3 防御分支）：仅清旧 handle，不做初始化 ──
        int dispatchBefore = logCount("onPackageReady: dispatch");
        FeishuKitModule m3 = newModule();
        FakeHandle h3 = new FakeHandle("FuckLarkSettings.gate#0");
        m3.onHotReloaded(new FakeReloadedParam("main", handles(h3), null));
        check(h3.unhookCount == 1, "bind 失败分支仍清掉旧 handle（防旧代驻留）");
        check(ModuleRuntime.module() == m2, "bind 失败后运行时保持原代");
        check(logCount("onHotReloaded: bind failed, stay fail-closed") == 1, "bind 失败已记日志");
        check(logCount("onPackageReady: dispatch") == dispatchBefore, "bind 失败分支无任何业务初始化");

        // ── 12. 同 loader 双实例 bind 拒绝语义保持（阶段 2 既有防御，非 reload 路径） ──
        int refuseBefore = logCount("refuse cross-generation re-bind");   // §11 bind 失败分支已记 1 次
        m3.onModuleLoaded(new FakeModuleLoadedParam());
        check(ModuleRuntime.module() == m2, "同 loader 跨代 bind 仍被拒（防旧代复活）");
        check(logCount("refuse cross-generation re-bind") == refuseBefore + 1, "跨代拒绝已记日志");

        // ── 13. GateSnapshot 点时快照性质（审计 P1 修复的结构性质） ──
        HotReloadSafety.resetForTest();
        HotReloadSafety.GateSnapshot snapClean = HotReloadSafety.inspectReloadSafety();
        check(snapClean.isClean() && "(clean)".equals(snapClean.describe())
                && !snapClean.hasNativeHooks() && !snapClean.hasModuleThreads()
                && !snapClean.hasExternalCallbacks() && !snapClean.hasJavaHooks(),
                "干净快照：isClean 与四类布尔、describe 三者一致");
        HotReloadSafety.markNativeHook("p1.native");
        check(snapClean.isClean() && "(clean)".equals(snapClean.describe()),
                "快照不可变：登记不影响已发出的快照（点时语义）");
        HotReloadSafety.GateSnapshot snapMarked = HotReloadSafety.inspectReloadSafety();
        check(!snapMarked.isClean() && snapMarked.hasNativeHooks()
                && !snapMarked.hasModuleThreads() && !snapMarked.hasExternalCallbacks()
                && !snapMarked.hasJavaHooks()
                && snapMarked.describe().contains("p1.native"),
                "登记后新快照如实反映类别与资源名");
        check(snapMarked.isClean() == !(snapMarked.hasNativeHooks()
                || snapMarked.hasModuleThreads() || snapMarked.hasExternalCallbacks()
                || snapMarked.hasJavaHooks()),
                "isClean 恒等于四类布尔之或的非");

        // ── 14. 并发回归：完整门控判定期间的登记不错误放行（审计 P1） ──
        // 两个写线程在门控判定全程反复 begin/end 延时任务（唯一可能的瞬态资源），
        // 主线程高频取快照：每次快照必须自洽（isClean ⇔ 三类布尔 ⇔ describe），
        // 且非空快照的原因必须就是该资源 —— 证明「查完一类到返回」之间无穿越窗口。
        HotReloadSafety.resetForTest();
        final int TOGGLES = 20000;
        Runnable toggler = new Runnable() {
            @Override public void run() {
                for (int i = 0; i < TOGGLES; i++) {
                    HotReloadSafety.beginDelayedTask("race.delayed");
                    HotReloadSafety.endDelayedTask("race.delayed");
                }
            }
        };
        Thread tw1 = new Thread(toggler, "race-writer-1");
        Thread tw2 = new Thread(toggler, "race-writer-2");
        tw1.start();
        tw2.start();
        int violations = 0;
        int accepts = 0;
        int rejects = 0;
        int rejectLogBefore = logCount("hot reload rejected");
        for (int i = 0; i < 20000; i++) {
            HotReloadSafety.GateSnapshot s = HotReloadSafety.inspectReloadSafety();
            boolean cats = s.hasNativeHooks() || s.hasModuleThreads() || s.hasExternalCallbacks()
                    || s.hasJavaHooks();
            if (s.isClean() == cats) violations++;                       // isClean 必须与四类布尔互补
            if (s.isClean() && !"(clean)".equals(s.describe())) violations++;
            if (!s.isClean() && !s.describe().contains("race.delayed")) violations++;
            if (i % 2500 == 0) {   // 周期性走完整入口门控（决策与原因同源自同一快照）
                RecordingReloadingParam p = new RecordingReloadingParam();
                if (m2.onHotReloading(p)) accepts++;
                else rejects++;
            }
        }
        tw1.join();
        tw2.join();
        check(violations == 0, "并发压力下 2 万次快照全部自洽（violations=" + violations + "）");
        check(HotReloadSafety.inspectReloadSafety().isClean()
                && "(clean)".equals(HotReloadSafety.describe()),
                "写线程结束后延时任务计数归零、回到干净态");
        check(accepts + rejects == 8
                && logCount("hot reload rejected") - rejectLogBefore == rejects,
                "并发下入口门控 8 次决策稳定且拒绝必记日志（accepts=" + accepts + " rejects=" + rejects + "）");
        // 发布屏障：登记线程完成后，后续任意次判定都不得漏检该资源（闩语义）
        final CountDownLatch published = new CountDownLatch(1);
        new Thread(new Runnable() {
            @Override public void run() {
                HotReloadSafety.markExternalCallback("race.cb");
                published.countDown();
            }
        }, "race-publisher").start();
        published.await();
        int misses = 0;
        for (int i = 0; i < 500; i++) {
            HotReloadSafety.GateSnapshot s = HotReloadSafety.inspectReloadSafety();
            if (s.isClean() || !s.describe().contains("race.cb")) misses++;
        }
        check(misses == 0, "已发布的外部回调登记在 500 次判定中零漏检");
        HotReloadSafety.resetForTest();
        check(HotReloadSafety.inspectReloadSafety().isClean()
                && m2.onHotReloading(new RecordingReloadingParam()) == true,
                "清理后门控恢复放行");

        // ── 15. 审计 F2（2026-10）：本代装过 Java hook / 已开始分发即拒绝（启动窗口竞态关闭） ──
        // 审计夹具场景：真实分发完成（Java hook 已在册）、配置桥接收器尚未注册、native/线程
        // 也未启动——原三类门控在此刻误判「干净」放行，框架卸掉旧 hook 后新代不重分发。
        resetRuntimeForNewGeneration();
        FeishuKitModule m4 = new FeishuKitModule();
        HookingXposed hx = new HookingXposed();
        m4.attachFramework(hx, new Runnable() { public void run() { } });
        m4.onModuleLoaded(new FakeModuleLoadedParam());
        m4.onPackageReady(new FakePackageParam(FEISHU, new URLClassLoader(new URL[0], null)));
        check(hx.installed > 0 && HookRuntime.snapshot().size() == hx.installed,
                "真实分发已装 Java hook（installed=" + hx.installed + "）");
        check(HotReloadSafety.hasJavaHooks() && !HotReloadSafety.hasNativeHooks()
                && !HotReloadSafety.hasModuleThreads() && !HotReloadSafety.hasExternalCallbacks(),
                "启动窗口复刻：仅 java-hooks 类登记，其余三类全空");
        check(HotReloadSafety.describe().contains("java-hooks=["),
                "describe 列出 java-hooks 分组: " + HotReloadSafety.describe());
        check(m4.onHotReloading(new RecordingReloadingParam()) == false,
                "已装 Java hook 的进程请求 reload 拒绝（审计 F2）");
        // 分发即登记的另一面：即使全部 hook 安装都失败（这里 hook() 直接抛异常），
        // 「分发开始」事实已登记，同样拒绝——fail-closed。
        HotReloadSafety.resetForTest();
        resetRuntimeForNewGeneration();
        FeishuKitModule m5 = new FeishuKitModule();
        m5.attachFramework(new FakeXposed(), new Runnable() { public void run() { } });
        m5.onModuleLoaded(new FakeModuleLoadedParam());
        m5.onPackageReady(new FakePackageParam(FEISHU, new URLClassLoader(new URL[0], null)));
        check(HotReloadSafety.hasJavaHooks(),
                "分发开始但零 hook 安装成功：dispatch-begun 登记仍在");
        check(m5.onHotReloading(new RecordingReloadingParam()) == false,
                "分发开始过但零 hook 的进程同样拒绝 reload（fail-closed）");
        HotReloadSafety.resetForTest();
        check(m5.onHotReloading(new RecordingReloadingParam()) == true,
                "清空登记（模拟换代）后恢复放行");

        System.out.println(failures == 0
                ? "== PASS：全部 hot reload 安全门控断言通过 =="
                : "== FAIL：" + failures + " 条断言未过 ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
