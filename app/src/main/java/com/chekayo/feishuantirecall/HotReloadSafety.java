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
 * 只做登记、不改变任何业务行为：各资源创建点在资源「已创建且无 teardown 能力」时打标。
 * 消费方（2026-10 审计 F2 第二轮起）：FeishuKitModule.onHotReloading 已无条件拒绝 reload
 * （放行是点时决策，无法与并发分发关闭竞争窗口，且没有旧代退役状态），本表不再参与
 * 放行判定，保留作 reload 诊断——拒绝日志附 {@link #describe()} 资源清单，也是未来实现
 * 「旧代退役 + 新代接管」时的资源盘点输入。第一版按表门控的设计与两轮收紧过程见
 * records/audit-fixes/audit-fixes-record.md。
 *
 * 门控判定必须走 {@link #inspectReloadSafety()}：三类资源与原因文本在同一次加锁内生成
 * 不可变 {@link GateSnapshot}，「查完一类到返回」之间不存在被并发打标穿越的窗口
 * （阶段 5 审计 P1 修复：原实现三类查询各自加锁，登记可插在两类查询之间导致错误放行）。
 * 三个 has* 查询只作诊断/测试用途，不得用于门控判定。
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
    private enum Kind { NATIVE_HOOK, MODULE_THREAD, EXTERNAL_CALLBACK, JAVA_HOOK }

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

    /**
     * 本代已装 Java hook / 已开始业务分发（诊断登记，审计 F2 引入的第四类）。
     * HookRuntime.hook 在安装锁内、任何 hook 对框架可见之前调用本方法；
     * FeishuKitModule.onPackageReady 在业务分发开始时调用。
     */
    public static void markJavaHook(String name) { latch(name, Kind.JAVA_HOOK); }

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

    /** 是否存在已装载且无 teardown 能力的 native inline hook（06 文档 §2 门控一）。仅诊断/测试用，门控判定走 {@link #inspectReloadSafety()}。 */
    public static boolean hasNativeHooks() { return hasKind(Kind.NATIVE_HOOK); }

    /** 是否存在模块自有线程或未完成的延时任务（06 文档 §2 门控二；§3.2 把延时任务归线程状态）。仅诊断/测试用，门控判定走 {@link #inspectReloadSafety()}。 */
    public static boolean hasModuleThreads() {
        synchronized (LOCK) {
            if (!PENDING_TASKS.isEmpty()) return true;
            return hasKindLocked(Kind.MODULE_THREAD);
        }
    }

    /** 是否存在已注册的外部回调（06 文档 §2 门控三）。仅诊断/测试用，门控判定走 {@link #inspectReloadSafety()}。 */
    public static boolean hasExternalCallbacks() { return hasKind(Kind.EXTERNAL_CALLBACK); }

    /** 是否已装 Java hook / 已开始业务分发（审计 F2 引入的第四类）。仅诊断/测试用。 */
    public static boolean hasJavaHooks() { return hasKind(Kind.JAVA_HOOK); }

    /**
     * 一次性完整判定快照（与 {@link #describe()} 同源同窗）。四类资源判定与原因文本在
     * 同一次加锁内生成，登记线程无法插在「查完一类到返回」之间；返回的快照不可变，
     * 发出后不受后续登记影响（点时语义）。onHotReloading 已无条件拒绝，本方法保留供
     * 诊断与测试（含并发回归），不得重新用作放行判定。
     */
    public static GateSnapshot inspectReloadSafety() {
        synchronized (LOCK) {
            return new GateSnapshot(
                    hasKindLocked(Kind.NATIVE_HOOK),
                    hasKindLocked(Kind.MODULE_THREAD) || !PENDING_TASKS.isEmpty(),
                    hasKindLocked(Kind.EXTERNAL_CALLBACK),
                    hasKindLocked(Kind.JAVA_HOOK),
                    describeLocked());
        }
    }

    /** 门控判定的不可变点时快照：isClean、四类布尔与 describe 来自同一加锁瞬间。 */
    public static final class GateSnapshot {
        private final boolean nativeHooks;
        private final boolean moduleThreads;
        private final boolean externalCallbacks;
        private final boolean javaHooks;
        private final String describe;

        private GateSnapshot(boolean nativeHooks, boolean moduleThreads,
                             boolean externalCallbacks, boolean javaHooks, String describe) {
            this.nativeHooks = nativeHooks;
            this.moduleThreads = moduleThreads;
            this.externalCallbacks = externalCallbacks;
            this.javaHooks = javaHooks;
            this.describe = describe;
        }

        /** 四类 teardown-unsafe 资源都不存在时为 true（第一版门控的放行条件；现仅供诊断/测试）。 */
        public boolean isClean() { return !nativeHooks && !moduleThreads && !externalCallbacks && !javaHooks; }

        public boolean hasNativeHooks() { return nativeHooks; }

        public boolean hasModuleThreads() { return moduleThreads; }

        public boolean hasExternalCallbacks() { return externalCallbacks; }

        public boolean hasJavaHooks() { return javaHooks; }

        /** 判定瞬间的资源清单文本；干净时为 "(clean)"。 */
        public String describe() { return describe; }
    }

    /**
     * 人类可读快照：拒绝原因日志与 reload 诊断用。
     * 全部为空时返回 "(clean)"，否则按 native/threads/callbacks/java-hooks 分组列出资源名。
     * 门控判定请改用 {@link #inspectReloadSafety()}（本方法只诊断，不保证与其它查询同窗）。
     */
    public static String describe() {
        synchronized (LOCK) {
            return describeLocked();
        }
    }

    /** describe 的锁内实现：与 inspectReloadSafety 共用，保证原因文本与四类判定同源同窗。 */
    private static String describeLocked() {
        if (LATCHED.isEmpty() && PENDING_TASKS.isEmpty()) return "(clean)";
        StringBuilder sb = new StringBuilder();
        appendKind(sb, Kind.NATIVE_HOOK, "native");
        appendKind(sb, Kind.MODULE_THREAD, "threads");
        appendKind(sb, Kind.EXTERNAL_CALLBACK, "callbacks");
        appendKind(sb, Kind.JAVA_HOOK, "java-hooks");
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
