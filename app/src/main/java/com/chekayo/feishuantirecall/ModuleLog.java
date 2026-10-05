package com.chekayo.feishuantirecall;

import android.util.Log;

import io.github.libxposed.api.XposedInterface;

/**
 * 统一日志入口：替代 legacy 日志通道（122 处已于阶段 4 全部切换）。
 *
 * 约束：
 * - 日志正文与 legacy 完全一致，便于新旧日志对照；
 * - 固定 tag（module.prop 的 name=FeishuKit），默认 Log.INFO 优先级；
 * - 模块未绑定（模块自身进程 / LauncherActivity）或 API 异常时安全降级为 no-op，
 *   与 legacy 在非注入进程的行为一致；protective：日志永不向业务抛异常。
 * 业务代码不得直接调用 XposedModule.log()。
 */
public final class ModuleLog {

    /** 与 module.prop name 键一致的全局固定 tag。 */
    public static final String TAG = "FeishuKit";

    private ModuleLog() {}

    /** 等价 legacy 日志(message)。 */
    public static void log(String message) {
        log(Log.INFO, message, null);
    }

    /** 带异常的变体：正文保持原样，异常经 API 的 throwable 通道上报。 */
    public static void log(String message, Throwable t) {
        log(Log.INFO, message, t);
    }

    /** 指定 android.util.Log 优先级的底层入口。 */
    public static void log(int priority, String message, Throwable t) {
        try {
            XposedInterface module = ModuleRuntime.module();
            if (module == null) return;
            if (t != null) module.log(priority, TAG, message, t);
            else module.log(priority, TAG, message);
        } catch (Throwable ignored) {
            // protective：任何框架侧日志异常都不允许波及业务路径
        }
    }
}
