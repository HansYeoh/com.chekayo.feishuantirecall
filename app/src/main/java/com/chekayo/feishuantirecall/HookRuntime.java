package com.chekayo.feishuantirecall;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * hook 注册与登记中心：替代 legacy API 的四类 hook 注册
 * （hookMethod / findAndHookMethod / hookAllMethods / hookAllConstructors，
 * 40 处注册点已于阶段 4 全部切换）。
 *
 * 核心调用是 API 102 的拦截器链：
 * {@code module.hook(executable).intercept(hooker)}；
 * 回调体一律拿到不可变的 {@code Chain}，改参用 {@code proceed(newArgs)}、
 * 短路直接 return 返回值、after 改返回值在 proceed() 之后 return 新值。
 *
 * 统一保证（业务类不得自建重复检测）：
 * - 每个 handle 以 process + package + ClassLoader identity + logicalId 幂等，
 *   同一 Executable 重复安装不报错、只告警并返回既有记录；同键不同 Executable
 *   视为 ID 配置冲突，抛 IllegalStateException 报出来（审计 F5）；
 * - 每个 handle 连同元数据登记在 REGISTRY，供卸载 / 同 ID 原子替换 / reload 诊断 / 测试期查重；
 *   首次安装即在安装锁内向 HotReloadSafety 登记诊断标记（markJavaHook）；
 * - 异常模式不在 per-hook 覆盖，跟随 module.prop 的 exceptionMode=protective 单一来源。
 */
public final class HookRuntime {

    private static final ConcurrentHashMap<String, InstalledHook> REGISTRY = new ConcurrentHashMap<String, InstalledHook>();
    private static final Object INSTALL_LOCK = new Object();

    private HookRuntime() {}

    /** 一条已安装 hook 的统一登记记录。 */
    public static final class InstalledHook {
        private final String registryKey;
        private final String logicalId;
        private final String processName;
        private final String packageName;
        private final ClassLoader classLoader;
        private final Executable executable;
        private final String signature;
        private final long installedAtMillis;
        private volatile XposedInterface.HookHandle apiHandle;

        InstalledHook(String registryKey, String logicalId, Executable executable, XposedInterface.HookHandle apiHandle) {
            this.registryKey = registryKey;
            this.logicalId = logicalId;
            this.processName = ModuleRuntime.getProcessName();
            this.packageName = ModuleRuntime.getPackageName();
            this.classLoader = ModuleRuntime.getClassLoader();
            this.executable = executable;
            this.signature = sig(executable);
            this.installedAtMillis = System.currentTimeMillis();
            this.apiHandle = apiHandle;
        }

        /** 安装时算好的幂等键；unhook 按它移除，不受之后 setTargetPackage 切换影响。 */
        public String getRegistryKey() { return registryKey; }
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
            REGISTRY.remove(registryKey, this);
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
    }

    /** 「声明类#名(参数类型)」人类可读签名，登记与冲突诊断用。 */
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

    // ── 核心 ────────────────────────────────────────────────────────────

    /**
     * 对已解析的 Method/Constructor 安装拦截器，登记并返回记录。
     * 重复（process+package+ClassLoader+logicalId 命中且 Executable 相同）时告警并返回既有记录，
     * 不重复安装；同键但 Executable 不同视为 logicalId 配置冲突，抛 IllegalStateException
     * 报出来而不是静默复用（审计 F5：不同声明类的同名方法曾被当成重复漏装还照常计数）。
     * @throws IllegalStateException 未绑定 ModuleRuntime（在入口生命周期之外调用），
     *         或 logicalId 与既有登记绑定了不同 Executable（ID 冲突）
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
                // Executable 不保证同一底层方法返回同一实例（getDeclaredMethods 每次新建），
                // 必须用 equals（声明类+名字+形参表）比较；引用比较会把幂等重装误判为冲突。
                if (!prev.getExecutable().equals(target)) {
                    throw new IllegalStateException("HookRuntime: id conflict " + logicalId
                            + " already bound to " + prev.getSignature()
                            + ", refusing " + sig(target));
                }
                ModuleLog.log("HookRuntime: skip duplicate install " + logicalId
                        + " -> " + prev.getSignature());
                return prev;
            }
            // 诊断登记（06 文档 §3 状态表）：本代装过任何 Java hook。登记发生在安装锁内、
            // hook 对框架可见之前，保证「查表者看到的登记」与「hook 的存在」时序一致
            // （onHotReloading 自 2026-10 审计 F2 第二轮起无条件拒绝，本登记供 reload
            // 诊断与未来「旧代退役」实现使用）。
            HotReloadSafety.markJavaHook("installed-java-hooks");
            XposedInterface.HookHandle api = module.hook(target)
                    .setId(key)
                    .intercept(hooker);
            InstalledHook rec = new InstalledHook(key, logicalId, target, api);
            REGISTRY.put(key, rec);
            ModuleLog.log("HookRuntime: installed " + logicalId + " -> " + rec.getSignature());
            return rec;
        }
    }

    // ── 便捷入口（阶段 4 逐点切换用） ──────────────────────────────────

    /**
     * 等价 legacy 的 findAndHookMethod(Class, name, paramTypes...)：精确查找后安装。
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
     * 等价 legacy 的 hookAllMethods：形参通配地 hook 本类<b>全部</b>同名 declared methods
     * （零参/带参都覆盖，不含继承，语义与 legacy 一致）。
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

    /** 等价 legacy 的 hookAllConstructors：形参通配地 hook 全部 declared constructors（含带参），id 规则同上。 */
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
