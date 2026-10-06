package com.chekayo.feishuantirecall;

import com.chekayo.larkresign.ResignTracker;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段 3 行为验证：宿主 JVM 直跑入口生命周期（产物不进 APK、不依赖真机）。
 * 业务 hook 走 legacy stubs（空操作），因此断言聚焦分发层可观测状态：
 * 各功能类的 PKG 锁定、ModuleRuntime 目标维度、入口日志、幂等表、hot reload 拒绝。
 * 覆盖（04 文档）：§2 onModuleLoaded 接线、§3 单一完整分发、§4 无双重分发、
 * §5 过滤保留、§6 幂等键 process+package+ClassLoader identity、fail-closed hot reload。
 */
public class EntryBehaviorTest {

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
            throw new UnsupportedOperationException("分发层测试不安装 hook");
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

    static class FakePackageLoadedParam implements io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam {
        final String pkg; final ClassLoader cl;
        FakePackageLoadedParam(String pkg, ClassLoader cl) { this.pkg = pkg; this.cl = cl; }
        public String getPackageName() { return pkg; }
        public android.content.pm.ApplicationInfo getApplicationInfo() { return null; }
        public boolean isFirstPackage() { return true; }
        public ClassLoader getDefaultClassLoader() { return cl; }
    }

    static class FakeHotReloadingParam implements HotReloadingParam {
        Object savedState;   // 阶段5：记录旧代传给新一代的门控结论
        public android.os.Bundle getExtras() { return null; }
        public void setSavedInstanceState(Object o) { this.savedState = o; }
    }

    static class FakeHotReloadedParam implements HotReloadedParam {
        public boolean isSystemServer() { return false; }
        public String getProcessName() { return "main"; }
        public android.os.Bundle getExtras() { return null; }
        public Object getSavedInstanceState() { return null; }
        public List<XposedInterface.HookHandle> getOldHookHandles() { return new ArrayList<XposedInterface.HookHandle>(); }
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

    static Object staticField(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    static final String FEISHU = "com.ss.android.lark";

    public static void main(String[] args) throws Exception {
        AntiRecall.g_lark_mark = null;   // 清白标探测缓存

        // ── 1. onModuleLoaded：进程级初始化接线（04 文档 §2） ──
        FeishuKitModule m = newModule();
        m.onModuleLoaded(new FakeModuleLoadedParam());
        check(ModuleRuntime.isBound(), "onModuleLoaded 后 ModuleRuntime 已绑定");
        check("main".equals(ModuleRuntime.getProcessName()), "进程名来自 ModuleLoadedParam（main）");
        check(logCount("ModuleRuntime: bound gen=") == 1 && logCount("api=102") == 1,
                "绑定日志记录 modern api=102 与 framework 信息");
        // fake 的 getModuleApplicationInfo=null → ModulePath fail-closed：字段保持 null，只记日志
        check(logCount("ModulePath: module apk path unavailable") == 1,
                "模块路径不可得时 fail-closed（保持 null 并记日志）");
        check(staticField(AntiRecall.class, "MODULE_PATH") == null,
                "未解析到路径时 AntiRecall.MODULE_PATH 保持 null（业务侧自行降级）");

        // ModulePath.apply 正路径：写入两个持有类（阶段 3 回收反射后的公开 setter）
        check(ModulePath.apply("/data/adb/modules/feishukit/base.apk"),
                "ModulePath.apply(路径) 成功");
        check("/data/adb/modules/feishukit/base.apk".equals(staticField(AntiRecall.class, "MODULE_PATH")),
                "AntiRecall.MODULE_PATH 已回写");
        check("/data/adb/modules/feishukit/base.apk".equals(staticField(ResignTracker.class, "MODULE_PATH")),
                "ResignTracker.MODULE_PATH 已回写");

        // ── 2. onPackageLoaded 只记录不分发（04 文档 §4 防双重分发） ──
        m.onPackageLoaded(new FakePackageLoadedParam(FEISHU, loader()));
        check(ModuleRuntime.getPackageName() == null, "onPackageLoaded 不触发分发（目标包仍 null）");
        check(logCount("onPackageReady: dispatch") == 0, "onPackageLoaded 无分发日志");

        // ── 3. 非目标包：过滤生效，不分发（04 文档 §5） ──
        m.onPackageReady(new FakePackageParam("com.other.app", loader()));
        check(ModuleRuntime.getPackageName() == null, "非目标包不分发");
        check(FEISHU.equals(staticField(AntiRecall.class, "PKG")), "非目标包不锁定 AntiRecall.PKG");
        AntiRecall.g_lark_mark = null;   // 清探测负缓存，避免影响后续用例

        // ── 4. 国内版包：完整分发一次（04 文档 §3） ──
        ClassLoader cl1 = loader();
        m.onPackageReady(new FakePackageParam(FEISHU, cl1));
        check(logCount("onPackageReady: dispatch " + FEISHU) == 1, "分发日志恰好 1 条");
        check(FEISHU.equals(ModuleRuntime.getPackageName()) && ModuleRuntime.getClassLoader() == cl1,
                "ModuleRuntime 目标包/ClassLoader 已设置");
        check(FEISHU.equals(staticField(AntiRecall.class, "PKG")), "AntiRecall.PKG 锁定");
        check(FEISHU.equals(staticField(ResignTracker.class, "PKG")), "ResignTracker.PKG 锁定");
        check(FEISHU.equals(staticField(FuckLarkSettings.class, "PKG")), "FuckLarkSettings.PKG 锁定");
        check(FEISHU.equals(staticField(ProfileCapture.class, "PKG")), "ProfileCapture.PKG 锁定");
        check(FEISHU.equals(staticField(DataViews.class, "PKG")), "DataViews.PKG 锁定");

        // ── 5. 幂等：同 process+package+ClassLoader 不二次分发（04 文档 §6） ──
        m.onPackageReady(new FakePackageParam(FEISHU, cl1));
        check(logCount("onPackageReady: dispatch " + FEISHU) == 1, "同 key 二次回调不分发");
        check(logCount("skip duplicate dispatch " + FEISHU) == 1, "重复分发记跳过日志");
        check(ModuleRuntime.getClassLoader() == cl1, "跳过时目标 ClassLoader 不变");

        // ── 6. 不同 ClassLoader = 新目标，重新分发（ClassLoader identity 参与幂等键） ──
        ClassLoader cl2 = loader();
        m.onPackageReady(new FakePackageParam(FEISHU, cl2));
        check(logCount("onPackageReady: dispatch " + FEISHU) == 2, "不同 ClassLoader 视为新目标重新分发");
        check(ModuleRuntime.getClassLoader() == cl2, "目标 ClassLoader 切到新 loader");

        // ── 7. 国际版包同代码自适应（isLarkFamily） ──
        m.onPackageReady(new FakePackageParam("com.larksuite.suite", loader()));
        check(logCount("onPackageReady: dispatch com.larksuite.suite") == 1, "国际版包名命中分发");
        check("com.larksuite.suite".equals(staticField(AntiRecall.class, "PKG")), "PKG 切到国际版");

        // ── 8. 幂等键构成 ──
        String key = FeishuKitModule.dispatchKey(FEISHU, cl1);
        check(key.startsWith("main|" + FEISHU + "|"), "幂等键 = process|package|ClassLoader identity");

        // ── 9. hot reload 门控（阶段5 起接 HotReloadSafety；2026-10 审计 F2 第二轮收紧为
        //      无条件拒绝——「干净放行」是点时决策，无法与并发分发关闭竞争窗口） ──
        HotReloadSafety.markExternalCallback("config-bridge.receiver(sync,pull)");   // 现网任意已分发进程都有配置桥接收器
        FakeHotReloadingParam rejectParam = new FakeHotReloadingParam();
        check(m.onHotReloading(rejectParam) == false, "持有外部回调时 onHotReloading 返回 false（拒绝）");
        check(logCount("hot reload rejected") == 1, "拒绝原因已记日志");
        check(rejectParam.savedState == null, "拒绝时不向新一代传门控结论");
        HotReloadSafety.resetForTest();
        FakeHotReloadingParam cleanParam = new FakeHotReloadingParam();
        check(m.onHotReloading(cleanParam) == false,
                "状态表清空后仍无条件拒绝（统一 fail-closed，不传门控结论）");
        check(cleanParam.savedState == null, "无条件拒绝同样不向新一代传任何状态");
        // 新一代入口（阶段5 起）：本测试同 loader 下 bind 幂等（真机新代是新 ClassLoader，bind 必成）
        m.onHotReloaded(new FakeHotReloadedParam());
        check(logCount("onHotReloaded: generation") == 1, "onHotReloaded 接线：bind + 旧 handle 清理 + ModulePath");

        // ── 10. 跨代 bind 拒绝（hot reload 防旧代复活） ──
        FeishuKitModule m2 = newModule();
        m2.onModuleLoaded(new FakeModuleLoadedParam());
        check(ModuleRuntime.module() != m2, "第二代实例 bind 被拒，运行时仍是首代");
        check(logCount("refuse cross-generation re-bind") == 1, "跨代拒绝已记日志");

        System.out.println(failures == 0
                ? "== PASS：全部入口生命周期断言通过 =="
                : "== FAIL：" + failures + " 条断言未过 ==");
        System.exit(failures == 0 ? 0 : 1);
    }

    static ClassLoader loader() { return new URLClassLoader(new URL[0], null); }
}
