package com.chekayo.feishuantirecall;

import com.chekayo.larkresign.ResignTracker;

import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 模块唯一 Java 入口（META-INF/xposed/java_init.list 注册），替代 legacy 的
 * 4 个包加载入口（AntiRecall / ResignTracker / FuckLarkSettingsHook /
 * ProfileCapture）。生命周期分发约定（04 文档）：
 *
 * - onModuleLoaded：只做进程级初始化（绑定 ModuleRuntime、回写两个 MODULE_PATH），
 *   此时没有目标包 ClassLoader，禁止安装业务 hook；
 * - onPackageReady：唯一完整业务分发入口；onPackageLoaded 只记日志，防止双重分发重复 hook；
 * - onHotReloading：fail-closed 返回 false——native inline hook / 安装轮询线程 / 配置桥
 *   接收器都没有 teardown 能力，拒绝 reload 优先于宣称支持（README §2 产品决策）；
 * - 不实现 onSystemServerStarting：模块没有 system server 业务，也不把作用域扩到 system server。
 *
 * 分发仍保留业务层过滤（modern scope 只决定注入范围，进程内可能加载多个 package）：
 * 国内版 / 国际版包名 + {@link AntiRecall#isLarkApp} 白标探测 + 各功能内部的
 * 主进程限制（AntiRecall native 层、ResignTracker）逐层不动。
 */
public final class FeishuKitModule extends XposedModule {

    /**
     * 已分发表：key = process|package|ClassLoader identity（04 文档 §6 幂等键）。
     * 不依赖业务类的全局 INSTALLED 布尔：一个进程可能加载多个 package，hot reload
     * 会产生新的模块代，不同进程各有一份本表。
     */
    private static final ConcurrentHashMap<String, Boolean> DISPATCHED =
            new ConcurrentHashMap<String, Boolean>();

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        if (!ModuleRuntime.bind(this, param.getProcessName())) {
            // 跨代重复 bind 被拒（hot reload 防旧代复活）；正常路径不可达，防御性直接返回
            return;
        }
        // 必须先于任何业务 hook 安装：AntiRecall 签名校验 / ResignTracker 抽 so 都读 MODULE_PATH
        ModulePath.apply(this);
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        // 只做轻量记录：完整分发在 onPackageReady，禁止在这里再装一遍（04 文档 §4）
        ModuleLog.log("onPackageLoaded: " + param.getPackageName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!ModuleRuntime.isBound()) {
            ModuleLog.log("onPackageReady before onModuleLoaded, skip dispatch");
            return;
        }
        String pkg = param.getPackageName();
        ClassLoader cl = param.getClassLoader();

        // 跨进程配置桥对任意注入进程安装（legacy handleLoadPackage 先于过滤执行，行为对齐；
        // 幂等由 CONFIG_BRIDGE_INSTALLED 保证）
        AntiRecall.installConfigBridge();

        // 国内版 / 国际版包名，或白标应用（包名任意但内核类保留）才继续
        if (!AntiRecall.isLarkFamily(pkg) && !AntiRecall.isLarkApp(cl)) return;

        String key = dispatchKey(pkg, cl);
        if (DISPATCHED.putIfAbsent(key, Boolean.TRUE) != null) {
            ModuleLog.log("onPackageReady: skip duplicate dispatch " + pkg + " (" + key + ")");
            return;
        }
        ModuleLog.log("onPackageReady: dispatch " + pkg + " (" + key + ")");

        ModuleRuntime.setTargetPackage(pkg, cl);

        // 完整分发（顺序沿用 legacy 入口注册表；安装顺序无相互依赖，仅保持可读对照）。
        // 各功能内部自带进程限制：AntiRecall native 层 / DownloadMirror / ResignTracker 仅主进程。
        AntiRecall.install(pkg, cl);
        ResignTracker.install(pkg, cl);
        FuckLarkSettings.install(pkg, cl);
        ProfileCapture.install(pkg, cl);
    }

    /** 幂等键：process + package + ClassLoader identity（04 文档 §6）。 */
    static String dispatchKey(String pkg, ClassLoader cl) {
        return ModuleRuntime.getProcessName() + "|" + pkg + "|" + System.identityHashCode(cl);
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        ModuleLog.log("hot reload rejected (fail-closed): native inline hooks / installer threads"
                + " / config bridge receiver have no teardown");
        return false;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        // onHotReloading 恒拒绝，此回调正常不可达；若框架强制换代，保留默认的旧 handle 清理
        ModuleLog.log("onHotReloaded: unexpected new generation " + param.getProcessName());
        super.onHotReloaded(param);
    }
}
