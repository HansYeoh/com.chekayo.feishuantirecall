package com.chekayo.feishuantirecall;

import android.content.pm.ApplicationInfo;

import com.chekayo.larkresign.ResignTracker;

import io.github.libxposed.api.XposedInterface;

/**
 * 模块 APK 路径桥接。legacy 经 {@code initZygote(StartupParam.modulePath)} 获得，
 * API 102 无 initZygote，改由 {@code getModuleApplicationInfo().sourceDir} 提供（阶段 3 的
 * onModuleLoaded 调用 {@link #apply}）。native loader 的 APK 内路径约定
 * （lib/arm64-v8a/libantirecall.so 等）不受影响。
 *
 * 写入走两个持有类的公开 setter（AntiRecall.setModulePath / ResignTracker.setModulePath，
 * 阶段 3 转换入口时补上，回收了本类初版的按字段名反射写入）。
 *
 * fail-closed：解析失败或写入失败均保持字段为 null 并记日志，
 * 业务侧既有 null 判断（AntiRecall 签名校验 / ResignTracker 启动）自行降级。
 */
public final class ModulePath {

    private ModulePath() {}

    /** 从模块实例解析模块 APK 路径；失败返回 null（记日志）。 */
    public static String resolve(XposedInterface module) {
        if (module == null) return null;
        try {
            ApplicationInfo ai = module.getModuleApplicationInfo();
            String path = (ai != null) ? ai.sourceDir : null;
            if (path == null) ModuleLog.log("ModulePath: getModuleApplicationInfo returned null sourceDir");
            return path;
        } catch (Throwable t) {
            ModuleLog.log("ModulePath: resolve failed", t);
            return null;
        }
    }

    /**
     * 把模块 APK 路径写入两个持有类的静态字段；失败返回 false。
     * 必须在业务 hook 安装前完成（阶段 3 的 onModuleLoaded 负责）。
     */
    public static boolean apply(String moduleApkPath) {
        if (moduleApkPath == null) {
            ModuleLog.log("ModulePath: module apk path unavailable, keep MODULE_PATH null");
            return false;
        }
        try {
            AntiRecall.setModulePath(moduleApkPath);
            ResignTracker.setModulePath(moduleApkPath);
            ModuleLog.log("ModulePath: MODULE_PATH = " + moduleApkPath);
            return true;
        } catch (Throwable t) {
            ModuleLog.log("ModulePath: set MODULE_PATH failed", t);
            return false;
        }
    }

    /** resolve + apply 一步到位。 */
    public static boolean apply(XposedInterface module) {
        return apply(resolve(module));
    }
}
