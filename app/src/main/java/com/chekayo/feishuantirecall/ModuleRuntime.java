package com.chekayo.feishuantirecall;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import java.util.UUID;

/**
 * modern 模块运行时的唯一状态持有者（libxposed API 102 桥接层）。
 *
 * 业务类一律经由本类获取 XposedInterface / 进程信息 / 目标 ClassLoader，
 * 不得自行缓存 XposedModule 实例。生命周期：
 * - 阶段 3 的入口在 onModuleLoaded() 调用 {@link #bind}（进程级，每代一次）；
 * - onPackageReady() 对每个目标包调用 {@link #setTargetPackage}。
 *
 * 同一进程内 static 状态天然按进程隔离；hot reload 产生新模块代时由新代入口重新 bind，
 * 跨代重复 bind 在此拒绝（防旧代实例复活）。hook 的统一登记见 {@link HookRuntime}。
 */
public final class ModuleRuntime {

    private static volatile XposedModule sModule = null;
    private static volatile String sGenerationId = null;
    private static volatile String sProcessName = null;
    private static volatile String sModuleApkPath = null;
    private static volatile String sPackageName = null;
    private static volatile ClassLoader sPackageClassLoader = null;

    private ModuleRuntime() {}

    /**
     * 绑定本代模块实例。同代重复绑定幂等；跨代绑定拒绝并返回 false（日志告警）。
     *
     * @param module      入口类自身（XposedModule 子类实例）
     * @param processName 当前进程名（来自 ModuleLoadedParam.getProcessName()）
     * @return 是否完成绑定
     */
    public static synchronized boolean bind(XposedModule module, String processName) {
        if (module == null) return false;
        if (sModule != null) {
            if (sModule == module) return true;
            ModuleLog.log("ModuleRuntime: refuse cross-generation re-bind (gen=" + sGenerationId + ")");
            return false;
        }
        sModule = module;
        sGenerationId = UUID.randomUUID().toString();
        sProcessName = processName;
        sModuleApkPath = ModulePath.resolve(module);
        ModuleLog.log("ModuleRuntime: bound gen=" + sGenerationId
                + " process=" + processName + " apk=" + sModuleApkPath
                + " api=" + module.getApiVersion()
                + " framework=" + module.getFrameworkName() + " " + module.getFrameworkVersion());
        return true;
    }

    /** 本代是否已绑定模块实例。 */
    public static boolean isBound() { return sModule != null; }

    /**
     * onPackageReady 阶段更新当前目标包与真实 ClassLoader。
     * 传 null 表示清除目标（非目标包进程），业务安装方须先判空。
     */
    public static synchronized void setTargetPackage(String packageName, ClassLoader classLoader) {
        sPackageName = packageName;
        sPackageClassLoader = classLoader;
    }

    /** 当前模块实例；未绑定（如模块自身进程）返回 null。 */
    public static XposedInterface module() { return sModule; }

    /** 本代唯一标识（首次 bind 时生成），hot reload 诊断用。未绑定为 null。 */
    public static String getGenerationId() { return sGenerationId; }

    /** 当前进程名。未绑定为 null。 */
    public static String getProcessName() { return sProcessName; }

    /** 模块 APK 路径（bind 时自 getModuleApplicationInfo().sourceDir 解析）。 */
    public static String getModuleApkPath() { return sModuleApkPath; }

    /** 当前目标包名；未在目标包进程则为 null。 */
    public static String getPackageName() { return sPackageName; }

    /** 当前目标包真实 ClassLoader；未在目标包进程则为 null。 */
    public static ClassLoader getClassLoader() { return sPackageClassLoader; }
}
