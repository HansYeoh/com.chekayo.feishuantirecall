package com.chekayo.feishuantirecall;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * hook 注册与登记中心：替代 XposedBridge.hookMethod / hookAllMethods / hookAllConstructors
 * 与 XposedHelpers.findAndHookMethod（现存 40 处注册点，阶段 4 逐点切换）。
 *
 * 核心调用是 API 102 的拦截器链：
 * {@code module.hook(executable).intercept(hooker)}；
 * 回调体一律拿到不可变的 {@code Chain}，改参用 {@code proceed(newArgs)}、
 * 短路直接 return 返回值、after 改返回值在 proceed() 之后 return 新值。
 *
 * 统一保证（业务类不得自建重复检测）：
 * - 每个 handle 以 process + package + ClassLoader identity + logicalId 幂等，
 *   重复安装不报错、只告警并返回既有记录；
 * - 每个 handle 连同元数据登记在 REGISTRY，供卸载 / 同 ID 原子替换 / reload 诊断 / 测试期查重；
 * - 异常模式不在 per-hook 覆盖，跟随 module.prop 的 exceptionMode=protective 单一来源。
 */
public final class HookRuntime {

    private static final ConcurrentHashMap<String, InstalledHook> REGISTRY = new ConcurrentHashMap<String, InstalledHook>();
    private static final Object INSTALL_LOCK = new Object();

    private HookRuntime() {}

    /** 一条已安装 hook 的统一登记记录。 */
    public static final class InstalledHook {
        private final String logicalId;
        private final String processName;
        private final String packageName;
        private final ClassLoader classLoader;
        private final Executable executable;
        private final String signature;
        private final long installedAtMillis;
        private volatile XposedInterface.HookHandle apiHandle;

        InstalledHook(String logicalId, Executable executable, XposedInterface.HookHandle apiHandle) {
            this.logicalId = logicalId;
            this.processName = ModuleRuntime.getProcessName();
            this.packageName = ModuleRuntime.getPackageName();
            this.classLoader = ModuleRuntime.getClassLoader();
            this.executable = executable;
            this.signature = sig(executable);
            this.installedAtMillis = System.currentTimeMillis();
            this.apiHandle = apiHandle;
        }

        public String getLogicalId() { return logicalId; }
        public String getProcessName() { return processName; }
        public String getPackageName() { return packageName; }
        public ClassLoader getClassLoader() { return classLoader; }
        public Executable getExecutable() { return executable; }
        /** 声明类#名(参数类型) 的人类可读签名，诊断用。 */
        public String getSignature() { return signature; }
        public long getInstalledAtMillis() { return installedAtMillis; }

        /** 卸载本 hook（Java-only hook 的卸载；native inline hook 不经此处）。 */
        public synchronized void unhook() {
            XposedInterface.HookHandle h = apiHandle;
            if (h == null) return;
            apiHandle = null;
            REGISTRY.remove(dedupeKey(logicalId), this);
            try {
                h.unhook();
            } catch (Throwable t) {
                ModuleLog.log("HookRuntime: unhook failed " + logicalId, t);
            }
        }

        /** API 102 同 ID 原子替换（阶段 5 hot reload 备用）。 */
        public synchronized InstalledHook replaceHook(XposedInterface.Hooker hooker) {
            XposedInterface.HookHandle h = apiHandle;
            if (h == null) throw new IllegalStateException("already unhooked: " + logicalId);
            apiHandle = h.replaceHook(hooker);
            return this;
        }

        private static String sig(Executable e) {
            StringBuilder sb = new StringBuilder(e.getDeclaringClass().getName()).append('#');
            if (e instanceof Method) sb.append(((Method) e).getName());
            else if (e instanceof Constructor) sb.append("<init>");
            sb.append('(');
            Class<?>[] ps = e.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(ps[i].getName());
            }
            return sb.append(')').toString();
        }
    }

    // ── 核心 ────────────────────────────────────────────────────────────

    /**
     * 对已解析的 Method/Constructor 安装拦截器，登记并返回记录。
     * 重复（process+package+ClassLoader+logicalId 命中）时告警并返回既有记录，不重复安装。
     * @throws IllegalStateException 未绑定 ModuleRuntime（说明在入口生命周期之外调用）
     */
    public static InstalledHook hook(Executable target, String logicalId, XposedInterface.Hooker hooker) {
        XposedInterface module = ModuleRuntime.module();
        if (module == null) {
            throw new IllegalStateException("HookRuntime.hook before ModuleRuntime.bind: " + logicalId);
        }
        final String key = dedupeKey(logicalId);
        synchronized (INSTALL_LOCK) {
            InstalledHook prev = REGISTRY.get(key);
            if (prev != null) {
                ModuleLog.log("HookRuntime: skip duplicate install " + logicalId
                        + " -> " + prev.getSignature());
                return prev;
            }
            XposedInterface.HookHandle api = module.hook(target)
                    .setId(key)
                    .intercept(hooker);
            InstalledHook rec = new InstalledHook(logicalId, target, api);
            REGISTRY.put(key, rec);
            ModuleLog.log("HookRuntime: installed " + logicalId + " -> " + rec.getSignature());
            return rec;
        }
    }

    // ── 便捷入口（阶段 4 逐点切换用） ──────────────────────────────────

    /**
     * 等价 XposedHelpers.findAndHookMethod(Class, name, paramTypes...)：精确查找后安装。
     * 找不到方法抛 NoSuchMethodError（带目标签名；legacy 抛受检 NoSuchMethodException，
     * 现役注册点全部 try/catch Throwable，行为不受影响）。
     */
    public static InstalledHook hookMethod(Class<?> clazz, String methodName, Class<?>[] parameterTypes,
                                           String logicalId, XposedInterface.Hooker hooker) {
        try {
            Method m = Reflect.findMethodExact(clazz, methodName, parameterTypes);
            return hook(m, logicalId, hooker);
        } catch (NoSuchMethodException e) {
            NoSuchMethodError err = new NoSuchMethodError("HookRuntime.hookMethod: " + e.getMessage()
                    + " (id=" + logicalId + ")");
            err.initCause(e);
            throw err;
        }
    }

    /** 等价 findAndHookMethod(String className, ClassLoader, ...) 重载：先经目标 ClassLoader 解析类。 */
    public static InstalledHook findAndHookMethod(String className, ClassLoader classLoader, String methodName,
                                                  Class<?>[] parameterTypes, String logicalId,
                                                  XposedInterface.Hooker hooker) {
        return hookMethod(Reflect.findClass(className, classLoader), methodName, parameterTypes, logicalId, hooker);
    }

    /**
     * 等价 XposedBridge.hookAllMethods：只 hook 本类 declared methods（不含继承，语义与 legacy 一致）。
     * 每个方法的 logicalId 为 prefix#0、prefix#1…；无匹配时返回空表（与 legacy 一致，不抛异常）。
     */
    public static List<InstalledHook> hookAllMethods(Class<?> clazz, String methodName,
                                                     String logicalIdPrefix, XposedInterface.Hooker hooker) {
        List<Method> found = Reflect.findDeclaredMethods(clazz, methodName);
        List<InstalledHook> out = new ArrayList<InstalledHook>(found.size());
        for (int i = 0; i < found.size(); i++) {
            out.add(hook(found.get(i), logicalIdPrefix + "#" + i, hooker));
        }
        ModuleLog.log("HookRuntime: hookAllMethods " + clazz.getName() + "#" + methodName
                + " matched " + out.size());
        return out;
    }

    /** 等价 XposedBridge.hookAllConstructors：hook 全部 declared constructors，id 规则同上。 */
    public static List<InstalledHook> hookAllConstructors(Class<?> clazz,
                                                          String logicalIdPrefix, XposedInterface.Hooker hooker) {
        List<Constructor<?>> found = Reflect.findDeclaredConstructors(clazz);
        List<InstalledHook> out = new ArrayList<InstalledHook>(found.size());
        for (int i = 0; i < found.size(); i++) {
            out.add(hook(found.get(i), logicalIdPrefix + "#" + i, hooker));
        }
        ModuleLog.log("HookRuntime: hookAllConstructors " + clazz.getName() + " matched " + out.size());
        return out;
    }

    // ── 登记表查询（reload 诊断 / 测试查重用） ─────────────────────────

    /** 当前登记快照（按安装顺序无保证）。 */
    public static List<InstalledHook> snapshot() {
        return new ArrayList<InstalledHook>(REGISTRY.values());
    }

    /** 按 logicalId 查（限定当前 process+package+ClassLoader 维度）。 */
    public static InstalledHook getByLogicalId(String logicalId) {
        return REGISTRY.get(dedupeKey(logicalId));
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    /**
     * 幂等键：process + package + ClassLoader identity + logicalId。
     * ClassLoader 无稳定 equals，用 identity；同键不同 Executable 视为冲突（防误配）。
     */
    private static String dedupeKey(String logicalId) {
        ClassLoader cl = ModuleRuntime.getClassLoader();
        return ModuleRuntime.getProcessName() + "|" + ModuleRuntime.getPackageName()
                + "|" + System.identityHashCode(cl) + "|" + logicalId;
    }
}
