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
 * - onHotReloading：无条件拒绝（2026-10 审计 F2 第二轮收紧）。第一版按 HotReloadSafety
 *   快照放行「干净」进程，但放行是点时决策——快照成立后并发分发线程仍可开始装 hook，
 *   且没有「返回 true 后旧代停止安装」的退役状态，竞争窗口无法彻底关闭；而真机现网
 *   任何进程都至少持有配置桥（阶段 7：allow 路径真机不可达），统一拒绝不损失现网能力。
 *   HotReloadSafety 状态表保留作 reload 诊断（拒绝日志附 describe 清单）。README §2：
 *   声明 autoHotReload=true 但安全拒绝优先，不宣称完整 reload 支持；
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

        // 诊断登记（06 文档 §3 状态表）：业务分发一开始（含任意注入进程的配置桥延迟
        // hook）即记「本代开始过分发」。2026-10 审计 F2 第二轮起 onHotReloading 无条件
        // 拒绝，本登记不再参与放行判定，保留为 reload 诊断与未来「旧代退役」实现的输入。
        HotReloadSafety.markJavaHook("dispatch-begun");

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
        // 2026-10 审计 F2 第二轮：无条件拒绝（复审推翻了第一版「干净放行」）。
        // 放行是点时决策：即使持安装锁取到干净快照，快照成立后并发分发线程仍可开始
        // 装 hook（dispatch-begun 登记只持 HotReloadSafety 内部锁，且即便把它移进安装
        // 锁，返回 true 之后也没有「本代退役、禁止继续分发/安装」的状态）——旧代继续
        // 装上的 hook 在框架换代后或泄漏或失效，竞争窗口无法用单次快照彻底关闭。
        // 真机现网任何已注入进程都至少持有配置桥接收器或其延迟 hook（阶段 7 记录：
        // allow 路径真机不可达），统一拒绝不损失现网能力；保留放行需要完整的旧代
        // 停止安装 + 新代接管协议，留待真正需要 hot reload 时实现。
        // HotReloadSafety 状态表保留作诊断：拒绝日志附 describe 资源清单。
        ModuleLog.log("hot reload rejected: unconditional fail-closed"
                + " (generation transition unsupported); safety=" + HotReloadSafety.describe());
        return false;
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
        // 签名校验都依赖）。业务分发不在此时执行：本代已无条件拒绝 reload（onHotReloading），
        // 本入口正常不可达，保留为防御性契约；即便框架异常强制换代，reload 也不会重放包
        // 回调，已分发的包无法在此补装——reload 后才加载的包照常触发本代 onPackageReady
        // 完整分发。
        ModulePath.apply(this);
        ModuleLog.log("onHotReloaded: new generation ready, safety=" + HotReloadSafety.describe());
    }
}
