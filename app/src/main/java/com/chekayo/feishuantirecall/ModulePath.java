package com.chekayo.feishuantirecall;

import android.content.pm.ApplicationInfo;

import io.github.libxposed.api.XposedInterface;

/**
 * 模块 APK 路径桥接。legacy 经 {@code initZygote(StartupParam.modulePath)} 获得，
 * API 102 无 initZygote，改由 {@code getModuleApplicationInfo().sourceDir} 提供（阶段 3 的
 * onModuleLoaded 调用 {@link #apply}）。native loader 的 APK 内路径约定
 * （lib/arm64-v8a/libantirecall.so 等）不受影响。
 *
 * AntiRecall.MODULE_PATH 与 ResignTracker.MODULE_PATH 均 package-private 且分属两个包，
 * 与 DataViews.java 读取同字段的既有反射模式保持一致，这里按字段名反射写入；
 * 阶段 3/4 若为两个持有类加公开 setter，可回收此反射。
 *
 * fail-closed：解析失败或写入失败均保持字段为 null 并记日志，
 * 业务侧既有 null 判断（AntiRecall 签名校验 / ResignTracker 启动）自行降级。
 */
public final class ModulePath {

    private static final String FIELD = "MODULE_PATH";
    private static final String[] HOLDERS = {
            "com.chekayo.feishuantirecall.AntiRecall",
            "com.chekayo.larkresign.ResignTracker",
    };

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
     * 把模块 APK 路径写入两个 legacy 静态字段；任一失败返回 false。
     * 必须在业务 hook 安装前完成（阶段 3 的 onModuleLoaded 负责）。
     */
    public static boolean apply(String moduleApkPath) {
        if (moduleApkPath == null) {
            ModuleLog.log("ModulePath: module apk path unavailable, keep " + FIELD + " null");
            return false;
        }
        boolean allOk = true;
        ClassLoader own = ModulePath.class.getClassLoader();
        for (String holder : HOLDERS) {
            try {
                Reflect.setStaticObjectField(Class.forName(holder, false, own), FIELD, moduleApkPath);
            } catch (Throwable t) {
                allOk = false;
                ModuleLog.log("ModulePath: set " + holder + "." + FIELD + " failed", t);
            }
        }
        if (allOk) ModuleLog.log("ModulePath: " + FIELD + " = " + moduleApkPath);
        return allOk;
    }

    /** resolve + apply 一步到位。 */
    public static boolean apply(XposedInterface module) {
        return apply(resolve(module));
    }
}
