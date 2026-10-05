package com.chekayo.feishuantirecall;

import com.chekayo.larkresign.ResignTracker;

import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;
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
 * - onHotReloading：按 {@link HotReloadSafety} 状态门控（06 文档 §2）——本代装过 native
 *   inline hook、模块自有线程或外部回调即 fail-closed 返回 false（这些资源都没有 teardown
 *   能力，放行会让旧代码和新代码叠加运行；配置桥接收器对任意注入进程安装，故所有已完成
 *   分发的进程都会拒绝）；只有什么都没装的注入进程才放行。README §2：声明 autoHotReload=true
 *   但安全拒绝优先，不宣称完整 reload 支持；
 * - onHotReloaded：新一代唯一生命周期入口（API 102 契约：reload 不重放 onModuleLoaded 与
 *   包回调）——bind 运行时 + 旧 handle 全量 unhook（框架默认实现）+ 补 ModulePath；
 *   reload 后新加载的包照常经 onPackageReady 进入完整分发；
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
        // 06 文档 §2 第一版策略：任一 teardown-unsafe 资源存在即拒绝（运行在旧代代码里）。
        // native inline hook / 安装与轮询线程 / 配置桥接收器都没有 unhook+dlclose /
        // 停线程 / unregister 的生命周期，放行 = 旧 hook 与新 hook 叠加、旧线程持有旧代引用。
        // 判定走单次锁内快照（阶段 5 审计 P1 修复）：三类资源与原因文本在同一次加锁内生成，
        // 登记线程无法插在「查完一类到返回」之间；快照不可变，决策与理由来自同一瞬间。
        HotReloadSafety.GateSnapshot safety = HotReloadSafety.inspectReloadSafety();
        if (!safety.isClean()) {
            ModuleLog.log("hot reload rejected: runtime is not teardown-safe -> " + safety.describe());
            return false;
        }
        try {
            // classloader-neutral 字符串：新一代在 onHotReloaded 读到的旧代门控结论（诊断用；
            // 放行与否的权威是框架只在本回调返回 true 后才换代这一契约本身）
            param.setSavedInstanceState("feishukit:generation-clean");
        } catch (Throwable ignored) {}
        ModuleLog.log("hot reload accepted: generation holds no teardown-unsafe resources");
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        // 06 文档 §4：运行在新一代代码里，是新一代唯一生命周期入口。只有旧代
        // onHotReloading 返回 true 才会换代；旧 handle 全部是 Java 拦截器（native inline
        // hook 不在框架 handle 列表里，且旧代持有 native/线程/回调时根本进不到这），
        // 框架默认实现 = 全量 unhook，即 06 文档 §4.2「可安全移除的 Java-only hook」。
        if (!ModuleRuntime.bind(this, param.getProcessName())) {
            // 新代 ClassLoader 下 static 全新、bind 正常必成；走到这说明同类加载器里出现
            // 双实例（框架异常）。fail-closed：仅清旧 handle 防旧代驻留，不做任何业务初始化。
            ModuleLog.log("onHotReloaded: bind failed, stay fail-closed (no init)");
            super.onHotReloaded(param);
            return;
        }
        ModuleLog.log("onHotReloaded: generation " + ModuleRuntime.getGenerationId()
                + " process=" + param.getProcessName()
                + " savedState=" + param.getSavedInstanceState()
                + " oldHandles=" + param.getOldHookHandles().size());
        try {
            for (XposedInterface.HookHandle h : param.getOldHookHandles()) {
                ModuleLog.log("onHotReloaded: old handle " + h.getId() + " -> " + h.getExecutable());
            }
        } catch (Throwable t) {
            ModuleLog.log("onHotReloaded: old handle dump failed", t);
        }
        super.onHotReloaded(param);
        // 06 文档 §4.4 现代入口初始化：bind + ModulePath（AntiRecall/ResignTracker 抽 so、
        // 签名校验都依赖）。业务分发不在此时执行：reload 得以发生 = 旧代没装过任何 hook
        //（门控保证），不存在需要补装的既有目标；reload 后才加载的包照常触发本代
        // onPackageReady 完整分发。
        ModulePath.apply(this);
        ModuleLog.log("onHotReloaded: new generation ready, safety=" + HotReloadSafety.describe());
    }
}
