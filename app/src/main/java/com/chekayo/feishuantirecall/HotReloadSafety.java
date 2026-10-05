package com.chekayo.feishuantirecall;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * hot reload 安全门控的资源状态表（06 文档 §3）。每个模块代一份：hot reload 新一代
 * 由新 ClassLoader 加载，static 状态天然按代隔离，新一代从空表起步，不存在跨代共享。
 *
 * 只做登记、不改变任何业务行为：各资源创建点在资源「已创建且无 teardown 能力」时打标，
 * 唯一的消费方是 {@link FeishuKitModule} 的 onHotReloading 门控 —— 存在任一 native inline
 * hook / 模块自有线程 / 外部回调即拒绝 reload（06 文档 §2 第一版策略：module.prop 声明
 * autoHotReload=true 但安全拒绝优先，避免旧代资源与新代代码叠加运行）。
 *
 * 登记语义（06 文档 §3.2/§3.3 的「是否启动/是否存在」）：
 * - native hook / 长生命周期线程 / 外部回调：一次性闩。第一版没有 teardown，
 *   这些资源在本代进程内不会消失，「已启动/已注册」即门控事实；
 * - ProfileCapture 延时任务：计数。任务体 run() 结束即递减，「是否存在」按未完成任务算。
 *
 * 门控查询与打标共用一把锁：onHotReloading 判空到返回之间不会被并发打标穿越
 * （框架本身对 reload 也按目标串行化，这里再加一层确定性）。
 */
public final class HotReloadSafety {

    /** 资源类别（延时任务不进本表，单独计数，查询归入线程类）。 */
    private enum Kind { NATIVE_HOOK, MODULE_THREAD, EXTERNAL_CALLBACK }

    private static final ConcurrentHashMap<String, Kind> LATCHED = new ConcurrentHashMap<String, Kind>();
    private static final ConcurrentHashMap<String, AtomicInteger> PENDING_TASKS = new ConcurrentHashMap<String, AtomicInteger>();
    private static final Object LOCK = new Object();

    private HotReloadSafety() {}

    /** native inline hook 已装载（System.load 返回即打标，实际 hook 安装可能稍后，偏保守）。 */
    public static void markNativeHook(String name) { latch(name, Kind.NATIVE_HOOK); }

    /** 模块自有线程已启动（第一版无停止信号，启动即视为常驻）。 */
    public static void markThread(String name) { latch(name, Kind.MODULE_THREAD); }

    /** 外部回调已注册（BroadcastReceiver / FileObserver 等模块持有的系统回调）。 */
    public static void markExternalCallback(String name) { latch(name, Kind.EXTERNAL_CALLBACK); }

    /** 延时任务入队（postDelayed 等）；任务体须在 finally 里配对 {@link #endDelayedTask}。 */
    public static void beginDelayedTask(String name) {
        if (name == null) return;
        synchronized (LOCK) {
            AtomicInteger n = PENDING_TASKS.get(name);
            if (n == null) PENDING_TASKS.put(name, new AtomicInteger(1));
            else n.incrementAndGet();
        }
    }

    /** 延时任务体结束；计数到 0 移除条目，重复调用不会计为负。 */
    public static void endDelayedTask(String name) {
        if (name == null) return;
        synchronized (LOCK) {
            AtomicInteger n = PENDING_TASKS.get(name);
            if (n == null) return;
            if (n.decrementAndGet() <= 0) PENDING_TASKS.remove(name);
        }
    }

    /** 是否存在已装载且无 teardown 能力的 native inline hook（06 文档 §2 门控一）。 */
    public static boolean hasNativeHooks() { return hasKind(Kind.NATIVE_HOOK); }

    /** 是否存在模块自有线程或未完成的延时任务（06 文档 §2 门控二；§3.2 把延时任务归线程状态）。 */
    public static boolean hasModuleThreads() {
        synchronized (LOCK) {
            if (!PENDING_TASKS.isEmpty()) return true;
            return hasKindLocked(Kind.MODULE_THREAD);
        }
    }

    /** 是否存在已注册的外部回调（06 文档 §2 门控三）。 */
    public static boolean hasExternalCallbacks() { return hasKind(Kind.EXTERNAL_CALLBACK); }

    /**
     * 人类可读快照：拒绝原因日志与 reload 诊断用。
     * 全部为空时返回 "(clean)"，否则按 native/threads/callbacks/delayed-tasks 分组列出资源名。
     */
    public static String describe() {
        synchronized (LOCK) {
            if (LATCHED.isEmpty() && PENDING_TASKS.isEmpty()) return "(clean)";
            StringBuilder sb = new StringBuilder();
            appendKind(sb, Kind.NATIVE_HOOK, "native");
            appendKind(sb, Kind.MODULE_THREAD, "threads");
            appendKind(sb, Kind.EXTERNAL_CALLBACK, "callbacks");
            if (!PENDING_TASKS.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append("delayed-tasks=[");
                boolean first = true;
                for (Map.Entry<String, AtomicInteger> e : PENDING_TASKS.entrySet()) {
                    if (!first) sb.append(',');
                    sb.append(e.getKey()).append('=').append(e.getValue().get());
                    first = false;
                }
                sb.append(']');
            }
            return sb.toString();
        }
    }

    /** 仅测试用：清空全部登记（宿主 JVM 行为测试的用例间隔离），生产代码不得调用。 */
    static void resetForTest() {
        synchronized (LOCK) {
            LATCHED.clear();
            PENDING_TASKS.clear();
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static void latch(String name, Kind kind) {
        if (name == null) return;
        synchronized (LOCK) {
            LATCHED.put(name, kind);
        }
    }

    private static boolean hasKind(Kind kind) {
        synchronized (LOCK) {
            return hasKindLocked(kind);
        }
    }

    private static boolean hasKindLocked(Kind kind) {
        for (Kind k : LATCHED.values()) {
            if (k == kind) return true;
        }
        return false;
    }

    private static void appendKind(StringBuilder sb, Kind kind, String label) {
        List<String> names = new ArrayList<String>();
        for (Map.Entry<String, Kind> e : LATCHED.entrySet()) {
            if (e.getValue() == kind) names.add(e.getKey());
        }
        if (names.isEmpty()) return;
        if (sb.length() > 0) sb.append(' ');
        sb.append(label).append("=[");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(names.get(i));
        }
        sb.append(']');
    }
}
