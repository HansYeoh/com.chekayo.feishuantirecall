import com.chekayo.feishuantirecall.HookRuntime;
import com.chekayo.feishuantirecall.ModuleRuntime;
import com.chekayo.feishuantirecall.Reflect;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 阶段 2 审计修复的行为验证：宿主 JVM 直跑（产物不进 APK、不依赖真机）。
 * 覆盖（对应审计意见）：
 * 1. hookAllMethods/hookAllConstructors 形参通配——零参/单参/多参方法、无参/带参构造函数全部覆盖，
 *    返回数量与 getDeclaredMethods/getDeclaredConstructors 一致；
 * 2. "空数组=仅零参"与"null=通配"的语义区分（P1 根因的文档性断言）；
 * 3. registry key 固化：setTargetPackage 切换后 unhook 仍按安装时 key 移除，无残留记录；
 * 4. callStaticMethod 只在 static 方法中选择（存在形参兼容的实例重载时不误选）。
 */
public class HookBridgeBehaviorTest {

    static int failures = 0;

    /** 被测夹具：3 构造函数 + 零参/单参/双份多参重载 + 同名 static/instance 方法对。 */
    public static class Fixture {
        public Fixture() {}
        public Fixture(int a) {}
        public Fixture(String a, int b) {}
        public void zero() {}
        public void one(int x) {}
        public void many(String a, int b, Object c) {}
        public void many(String a) {}
        public static void dup(Object o) {}
        public void dup(int x) {}
    }

    // ── 伪造框架（XposedInterface 全部为接口，可直接实现；XposedModule 构造后 attachFramework） ──

    static int installCount = 0;
    static int frameworkUnhookCount = 0;

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
        public void unhook() { frameworkUnhookCount++; }
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

    static void check(boolean cond, String what) {
        System.out.println((cond ? "[PASS] " : "[FAIL] ") + what);
        if (!cond) failures++;
    }

    static ClassLoader uniqueLoader() { return new URLClassLoader(new URL[0], null); }

    public static void main(String[] args) throws Exception {
        // ── 1. Reflect 通配枚举：数量与 getDeclared* 一致 ──
        int declaredMany = 0;
        for (Method m : Fixture.class.getDeclaredMethods()) {
            if (m.getName().equals("many")) declaredMany++;
        }
        check(declaredMany == 2 && Reflect.findDeclaredMethods(Fixture.class, "many").size() == declaredMany,
                "findDeclaredMethods(many) 通配数量 == 2（单参+三参重载都覆盖）");
        check(Reflect.findDeclaredMethods(Fixture.class, "zero").size() == 1, "零参方法被通配覆盖");
        check(Reflect.findDeclaredMethods(Fixture.class, "one").size() == 1, "单参方法被通配覆盖");
        int ctorCount = Fixture.class.getDeclaredConstructors().length;
        check(ctorCount == 3 && Reflect.findDeclaredConstructors(Fixture.class).size() == ctorCount,
                "findDeclaredConstructors 通配数量 == 3（含带参构造）");

        // ── 2. 空数组 vs null 的语义区分（P1 根因的回归断言） ──
        check(Reflect.findDeclaredMethods(Fixture.class, "many", new Class<?>[0]).isEmpty(),
                "空数组=仅匹配零参（many* 全带参 → 0 命中）");
        check(Reflect.findDeclaredMethods(Fixture.class, "zero", new Class<?>[0]).size() == 1,
                "空数组对零参方法 zero 命中 1");
        check(Reflect.findDeclaredMethods(Fixture.class, "many", (Class<?>[]) null).size() == 2,
                "null=签名通配（many 命中 2）");
        check(Reflect.findDeclaredConstructors(Fixture.class, new Class<?>[0]).size() == 1,
                "构造函数：空数组=仅无参（命中 1）");
        check(Reflect.findDeclaredConstructors(Fixture.class, (Class<?>[]) null).size() == 3,
                "构造函数：null=通配（命中 3）");

        // ── 3. HookRuntime.hookAll*：fake 框架侧实际安装数量一致 ──
        XposedInterface fake = new FakeXposed();
        FakeModule module = new FakeModule();
        module.attachFramework(fake, new Runnable() { public void run() { } });
        check(ModuleRuntime.bind(module, "test.process"), "ModuleRuntime.bind(伪造模块)");

        XposedInterface.Hooker noop = new XposedInterface.Hooker() {
            public Object intercept(XposedInterface.Chain chain) throws Throwable { return chain.proceed(); }
        };

        ModuleRuntime.setTargetPackage("pkgA", uniqueLoader());
        int before = installCount;
        List<HookRuntime.InstalledHook> hookedMany =
                HookRuntime.hookAllMethods(Fixture.class, "many", "t/many", noop);
        check(hookedMany.size() == 2 && installCount - before == 2,
                "hookAllMethods(many) 返回 2 条且框架实际安装 2 次（含带参）");

        before = installCount;
        List<HookRuntime.InstalledHook> hookedCtors =
                HookRuntime.hookAllConstructors(Fixture.class, "t/ctor", noop);
        check(hookedCtors.size() == 3 && installCount - before == 3,
                "hookAllConstructors 返回 3 条且实际安装 3 次（含带参构造）");

        // 全名字遍历总和 == 全部 declared methods（审计要求的数量一致性）
        Set<String> names = new HashSet<String>();
        for (Method m : Fixture.class.getDeclaredMethods()) names.add(m.getName());
        int total = 0;
        for (String n : names) {
            total += HookRuntime.hookAllMethods(Fixture.class, n, "t/all/" + n, noop).size();
        }
        check(total == Fixture.class.getDeclaredMethods().length,
                "hookAllMethods 全名字遍历总和(" + total + ") == getDeclaredMethods 数量");

        // 重复安装幂等：同 id 同维度不再触发框架安装
        before = installCount;
        List<HookRuntime.InstalledHook> again =
                HookRuntime.hookAllMethods(Fixture.class, "many", "t/many", noop);
        check(again.size() == 2 && installCount - before == 0, "重复 hookAll* 幂等（框架 0 新安装）");

        // ── 4. registry key 固化：切包后 unhook 按安装时 key 移除 ──
        ClassLoader loaderA = uniqueLoader(), loaderB = uniqueLoader();
        ModuleRuntime.setTargetPackage("pkgA", loaderA);
        HookRuntime.InstalledHook h0 =
                HookRuntime.hookAllMethods(Fixture.class, "one", "t/one", noop).get(0);
        check(HookRuntime.getByLogicalId("t/one#0") == h0, "安装后 getByLogicalId(t/one#0) 命中");
        ModuleRuntime.setTargetPackage("pkgB", loaderB);
        h0.unhook();
        check(frameworkUnhookCount >= 1, "unhook 调用了框架 HookHandle.unhook");
        ModuleRuntime.setTargetPackage("pkgA", loaderA);
        check(HookRuntime.getByLogicalId("t/one#0") == null,
                "切包后 unhook → 切回 pkgA 无残留记录（按安装时 key 移除，可重新安装）");
        before = installCount;
        HookRuntime.InstalledHook h0b =
                HookRuntime.hookAllMethods(Fixture.class, "one", "t/one", noop).get(0);
        check(h0b != h0 && installCount - before == 1, "同 logicalId 卸载后可重新安装（不被误判重复）");

        // ── 5. callStaticMethod 静态筛选：存在形参兼容的实例重载时不误选 ──
        // static dup(Object) 得分 2 / instance dup(int) 得分 0；未筛选会选中实例方法并以
        // invoke(null,….) 抛 NPE。
        boolean staticThrew = false;
        try { Reflect.callStaticMethod(Fixture.class, "dup", 42); }
        catch (Throwable t) { staticThrew = true; }
        check(!staticThrew, "callStaticMethod(dup,42) 不抛（只在 static 中选择）");

        boolean instThrew = false;
        try { Reflect.callMethod(new Fixture(), "dup", 42); }
        catch (Throwable t) { instThrew = true; }
        check(!instThrew, "callMethod(dup,42) 命中实例 dup(int) 不抛");

        System.out.println(failures == 0
                ? "== PASS：全部行为断言通过 =="
                : "== FAIL：" + failures + " 条断言未过 ==");
        System.exit(failures == 0 ? 0 : 1);
    }
}
