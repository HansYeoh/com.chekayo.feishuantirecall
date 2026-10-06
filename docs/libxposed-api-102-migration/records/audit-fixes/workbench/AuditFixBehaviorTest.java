package com.chekayo.feishuantirecall;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2026-10 分支审计修复回归（宿主 JVM 直跑，产物不进 APK、不依赖真机）。
 * 逐项锁定审计 F1/F2/F4/F5 的修复行为（F3 是 module.prop 元数据契约，由
 * check-audit-fixes.sh 的 grep 断言覆盖，不在本测试内）：
 *
 * - F1 修复断言：AiPeekBlock addView/setVisibility、AntiRecall ReadReqHook 短参数路径、
 *   InvokeHook 提前放行分支、MapperHook 提前返回分支——宿主异常必须原样上抛，
 *   且原方法只执行一次（修复前：异常被吞、proceed 被二次调用）；
 * - F2 修复断言：真实分发后（Java hook 在册、其余三类资源全空的启动窗口形态）
 *   onHotReloading 必须拒绝；分发开始但零 hook 安装成功同样拒绝（fail-closed）；
 *   清空登记（模拟换代）后恢复放行；
 * - F4 修复断言：findMethodExact 不上溯父类（子类未覆盖时抛 NoSuchMethodException
 *   而不是误挂父类实现）、int/Integer 按 Class 身份严格区分；callMethod 的
 *   best-match 沿父类语义保持不变；
 * - F5 修复断言：两个实现类的同名审计方法各装一个 hook（逻辑 ID 含声明类+签名）；
 *   同 logicalId 绑定不同 Executable 抛 IllegalStateException（id conflict）；
 *   同键同 Executable 仍幂等。
 */
public class AuditFixBehaviorTest {

    static int failures = 0;

    static void check(boolean cond, String what) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (!cond) failures++;
    }

    // ── 故障注入 Chain：首次 proceed 抛宿主异常（副作用已发生），二次成功 ──
    static class ThrowOnceChain implements XposedInterface.Chain {
        int calls;
        Object[] args;
        final RuntimeException failure = new IllegalStateException("host failure after side effect");
        ThrowOnceChain(Object... args) { this.args = args; }
        public Executable getExecutable() { return null; }
        public Object getThisObject() { return null; }
        public List<Object> getArgs() { return Arrays.asList(args); }
        public Object getArg(int i) { return args[i]; }
        public Object proceed() { return proceed(args); }
        public Object proceed(Object[] a) {
            if (++calls == 1) throw failure;
            return "second invocation result";
        }
        public Object proceedWith(Object receiver) { return proceed(); }
        public Object proceedWith(Object receiver, Object[] a) { return proceed(a); }
    }

    /** 捕获安装的 hooker（按 setId 传入的完整键记录），供 F1 故障注入。 */
    static class CapturingXposed implements XposedInterface {
        final Map<String, XposedInterface.Hooker> hooks = new LinkedHashMap<String, XposedInterface.Hooker>();
        public int getApiVersion() { return API_102; }
        public String getFrameworkName() { return "fake"; }
        public String getFrameworkVersion() { return "0.0"; }
        public long getFrameworkVersionCode() { return 0L; }
        public long getFrameworkProperties() { return 0L; }
        public XposedInterface.HookBuilder hook(final Executable target) {
            return new XposedInterface.HookBuilder() {
                String id;
                public XposedInterface.HookBuilder setPriority(int p) { return this; }
                public XposedInterface.HookBuilder setExceptionMode(XposedInterface.ExceptionMode m) { return this; }
                public XposedInterface.HookBuilder setId(String value) { id = value; return this; }
                public XposedInterface.HookHandle intercept(final XposedInterface.Hooker h) {
                    hooks.put(id, h);
                    return new XposedInterface.HookHandle() {
                        public Executable getExecutable() { return target; }
                        public void unhook() { }
                        public String getId() { return id; }
                        public XposedInterface.HookHandle replaceHook(XposedInterface.Hooker n) { return this; }
                    };
                }
            };
        }
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

    /** hook() 直接抛异常的伪造框架：模拟「分发开始但零 hook 安装成功」（F2 fail-closed 面）。 */
    static class ThrowingXposed extends CapturingXposed {
        public XposedInterface.HookBuilder hook(Executable target) {
            throw new UnsupportedOperationException("all installs fail");
        }
    }

    static class FakeModuleLoadedParam implements ModuleLoadedParam {
        public boolean isSystemServer() { return false; }
        public String getProcessName() { return "auditfix"; }
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

    static class FakeHotReloadingParam implements io.github.libxposed.api.XposedModuleInterface.HotReloadingParam {
        public android.os.Bundle getExtras() { return null; }
        public void setSavedInstanceState(Object o) { }
    }

    // ── F4 夹具：父子类与 int/Integer 重载 ──
    public static class Parent { public void onResume() { } }
    public static class Child extends Parent { }
    public static class ExactOverloads {
        public void value(Integer n) { }
        public void value(int n) { }
    }

    // ── F5 夹具：两个同名审计方法的实现类 ──
    public static class AuditA { public void auditImageDownload(String x) { } }
    public static class AuditB { public void auditImageDownload(String x) { } }

    static XposedInterface.Hooker bySuffix(Map<String, XposedInterface.Hooker> hooks, String suffix) {
        for (Map.Entry<String, XposedInterface.Hooker> e : hooks.entrySet()) {
            if (e.getKey().endsWith("|" + suffix)) return e.getValue();
        }
        return null;
    }

    /** 断言：回调把宿主异常原样上抛，且原方法只执行了一次（F1 修复的核心行为）。 */
    static void checkSingleProceedAndRethrow(XposedInterface.Hooker hooker, ThrowOnceChain chain, String what) {
        boolean rethrown = false;
        try {
            hooker.intercept(chain);
        } catch (Throwable t) {
            rethrown = (t == chain.failure);
        }
        check(rethrown && chain.calls == 1,
                what + "（异常原样上抛=" + rethrown + ", proceedCalls=" + chain.calls + "）");
    }

    public static void main(String[] args) throws Throwable {
        AntiRecall.g_lark_mark = null;   // 清白标探测缓存
        HotReloadSafety.resetForTest();

        // ── 真实分发一次：拿到生产 Hooker + F2 的启动窗口形态 ──
        CapturingXposed fx = new CapturingXposed();
        FeishuKitModule module = new FeishuKitModule();
        module.attachFramework(fx, new Runnable() { public void run() { } });
        module.onModuleLoaded(new FakeModuleLoadedParam());
        module.onPackageReady(new FakePackageParam(
                "com.ss.android.lark", new URLClassLoader(new URL[0], null)));
        check(fx.hooks.size() > 0 && HookRuntime.snapshot().size() == fx.hooks.size(),
                "真实分发已装 hook（installed=" + fx.hooks.size() + "）");

        // ══ F2：已装 Java hook 的进程请求 reload 必须拒绝（启动窗口竞态关闭） ══
        check(HotReloadSafety.hasJavaHooks() && !HotReloadSafety.hasNativeHooks()
                && !HotReloadSafety.hasModuleThreads() && !HotReloadSafety.hasExternalCallbacks(),
                "启动窗口形态：仅 java-hooks 登记，native/线程/接收器全空");
        check(HotReloadSafety.describe().contains("java-hooks=["),
                "describe 列出 java-hooks 分组: " + HotReloadSafety.describe());
        check(module.onHotReloading(new FakeHotReloadingParam()) == false,
                "F2: 已装 hook、接收器未注册的进程请求 reload 拒绝");
        HotReloadSafety.resetForTest();
        check(module.onHotReloading(new FakeHotReloadingParam()) == true,
                "F2: 清空登记（模拟换代）后恢复放行");
        // 分发开始但零 hook 安装成功：dispatch-begun 登记仍在，依旧拒绝（fail-closed）
        HotReloadSafety.resetForTest();
        CapturingXposed tx = new ThrowingXposed();
        FeishuKitModule m2 = new FeishuKitModule();
        m2.attachFramework(tx, new Runnable() { public void run() { } });
        m2.onModuleLoaded(new FakeModuleLoadedParam());
        m2.onPackageReady(new FakePackageParam(
                "com.ss.android.lark", new URLClassLoader(new URL[0], null)));
        check(HotReloadSafety.hasJavaHooks() && tx.hooks.isEmpty(),
                "F2: 零 hook 安装成功但 dispatch-begun 登记仍在");
        check(m2.onHotReloading(new FakeHotReloadingParam()) == false,
                "F2: 分发开始过但零 hook 的进程拒绝 reload");
        HotReloadSafety.resetForTest();

        // ══ F1：宿主异常原样上抛，原方法只执行一次 ══
        // AiPeekBlock.addView（开关关：原走「proceed 在 catch 内」分支）
        XposedInterface.Hooker addHook = bySuffix(fx.hooks, "aipeek.addView.1");
        check(addHook != null, "F1 前置: 捕获到生产 addView hooker");
        Config.blockaipeek = false;
        checkSingleProceedAndRethrow(addHook, new ThrowOnceChain((Object) null),
                "F1: addView 开关关——宿主异常上抛且原方法只执行一次");
        // AiPeekBlock.setVisibility（开关关）
        XposedInterface.Hooker visHook = bySuffix(fx.hooks, "aipeek.setVisibility");
        check(visHook != null, "F1 前置: 捕获到生产 setVisibility hooker");
        checkSingleProceedAndRethrow(visHook, new ThrowOnceChain(0),
                "F1: setVisibility 开关关——宿主异常上抛且原方法只执行一次");
        // ReadReqHook 短参数路径（修复前：proceed 在 catch 内被吞后再执行一次）
        AntiRecall.ReadReqHook readHook = new AntiRecall.ReadReqHook();
        checkSingleProceedAndRethrow(readHook, new ThrowOnceChain(new Object[]{ "short" }),
                "F1: ReadReqHook 短参数构造——宿主异常上抛且原构造只执行一次");
        // InvokeHook 提前放行分支（a0=null）
        AntiRecall.InvokeHook invokeHook = new AntiRecall.InvokeHook();
        checkSingleProceedAndRethrow(invokeHook, new ThrowOnceChain(new Object[]{ null }),
                "F1: InvokeHook 空参分支——宿主异常上抛且原调用只执行一次");
        // InvokeHook 哨兵噪音分支（cmd=10000 → 仅放行）
        ThrowOnceChain cSentinel = new ThrowOnceChain(10000);
        checkSingleProceedAndRethrow(invokeHook, cSentinel,
                "F1: InvokeHook 10000 哨兵分支——宿主异常上抛且原调用只执行一次");
        // MapperHook 提前返回分支（TAMPER=2 仅放行）
        AntiRecall.TAMPER = 2;
        AntiRecall.MapperHook mapper = new AntiRecall.MapperHook(new Object());
        checkSingleProceedAndRethrow(mapper, new ThrowOnceChain((Object) null),
                "F1: MapperHook 被篡改分支——宿主异常上抛且原方法只执行一次");
        AntiRecall.TAMPER = 0;
        Config.blockaipeek = true;
        // （MapperHook/ReadReqHook/InvokeHook 正常路径行为不变由阶段 4 行为测试回归覆盖，
        //   本测试只锁定审计 F1 的异常边界。）

        // ══ F4：精确查找 declared+严格身份；best-match 语义保留 ══
        boolean noInherited = false;
        try {
            Reflect.findMethodExact(Child.class, "onResume");
        } catch (NoSuchMethodException e) {
            noInherited = true;
        }
        check(noInherited, "F4: 子类未覆盖时精确查找不上溯（不再误挂 Parent.onResume）");
        Method byInt = Reflect.findMethodExact(ExactOverloads.class, "value", int.class);
        Method byInteger = Reflect.findMethodExact(ExactOverloads.class, "value", Integer.class);
        check(byInt.getParameterTypes()[0] == int.class && byInteger.getParameterTypes()[0] == Integer.class,
                "F4: int 与 Integer 按 Class 身份严格区分，各命中各的重载");
        Reflect.callMethod(new Child(), "onResume");
        check(true, "F4 语义保留: callMethod best-match 仍可调父类声明的方法");

        // ══ F5：动态实现类 ID 含声明类+签名；同键不同 Executable 报冲突 ══
        Method a1 = AuditA.class.getDeclaredMethod("auditImageDownload", String.class);
        Method b1 = AuditB.class.getDeclaredMethod("auditImageDownload", String.class);
        int before = HookRuntime.snapshot().size();
        int n = FileDownloadUnlock.hookNamedVoidMethods(AuditA.class, "auditImageDownload")
                + FileDownloadUnlock.hookNamedVoidMethods(AuditB.class, "auditImageDownload");
        check(n == 2 && HookRuntime.snapshot().size() - before == 2,
                "F5: 两个实现类的同名审计方法各装一个 hook（installed=" + n + "）");
        String idA = FileDownloadUnlock.auditId("dlunlock.svcaudit", AuditA.class, a1);
        String idB = FileDownloadUnlock.auditId("dlunlock.svcaudit", AuditB.class, b1);
        check(!idA.equals(idB)
                && HookRuntime.getByLogicalId(idA) != null
                && HookRuntime.getByLogicalId(idB) != null,
                "F5: 逻辑 ID 含声明类+方法名+形参表，两条登记都可查到");
        XposedInterface.Hooker noop = new XposedInterface.Hooker() {
            public Object intercept(XposedInterface.Chain chain) throws Throwable { return chain.proceed(); }
        };
        HookRuntime.hook(a1, "auditfix.conflict", noop);
        boolean conflict = false;
        String conflictMsg = null;
        try {
            HookRuntime.hook(b1, "auditfix.conflict", noop);
        } catch (IllegalStateException e) {
            conflict = e.getMessage() != null && e.getMessage().contains("id conflict");
            conflictMsg = e.getMessage();
        }
        check(conflict, "F5: 同 logicalId 绑定不同 Executable 抛 id conflict: " + conflictMsg);
        HookRuntime.InstalledHook first = HookRuntime.hook(a1, "auditfix.dedupe", noop);
        HookRuntime.InstalledHook second = HookRuntime.hook(a1, "auditfix.dedupe", noop);
        check(first == second, "F5 语义保留: 同键同 Executable 仍幂等返回既有记录");

        HotReloadSafety.resetForTest();
        System.out.println(failures == 0
                ? "== PASS：全部审计修复回归断言通过 =="
                : "== FAIL：" + failures + " 条断言未过 ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
