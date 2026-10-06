# 阶段 5：hot reload 安全策略

## 目标

接入 API 102 的 hot reload 生命周期，但在当前 native/线程 teardown 能力不足时安全拒绝 reload，避免旧代码和新代码叠加运行。

## 1. 当前不能直接放行的原因

当前代码包含：

- `AntiRecall` native loader 和持续维护线程。
- `ResignTracker` 的 native 初始化和长期轮询线程。
- `ProfileCapture` 的 delayed Runnable。
- 配置桥注册的 BroadcastReceiver。
- native inline hook。
- `System.load()` 加载的模块 native library。
- 没有完整的 native unhook / `dlclose` 生命周期。

API 102 要求模块在允许旧 generation 退休前完成这些资源的清理。当前不能靠简单地重新执行入口解决。

## 2. 第一版策略

> **2026-10 审计更新（两轮收紧）**：本节第一版「按资源状态放行干净进程」的策略已被推翻——放行是点时决策，无法与并发分发关闭竞争窗口（复审并发夹具：干净快照成立后另一线程仍可完成 19 个 hook 安装），且没有「返回 true 后旧代停止安装」的退役状态。当前实现为**无条件拒绝**：`onHotReloading` 统一返回 false（真机现网任何已注入进程都至少持有配置桥，allow 路径本就不可达，统一拒绝不损失现网能力）；`HotReloadSafety` 状态表保留作拒绝日志的诊断清单。资源登记从三类扩为四类（新增已装 Java hook）与门控改无条件拒绝的过程、证据见 `records/audit-fixes/audit-fixes-record.md`。下文保留第一版设计原文供追溯。

`module.prop`：

```properties
autoHotReload=true
```

入口实现：

```java
@Override
public boolean onHotReloading(HotReloadingParam param) {
    if (HotReloadSafety.hasNativeHooks()
            || HotReloadSafety.hasModuleThreads()
            || HotReloadSafety.hasExternalCallbacks()) {
        ModuleLog.log("hot reload rejected: runtime is not teardown-safe");
        return false;
    }
    return true;
}
```

这意味着：

- 框架可以进入 reload 协商流程。
- 当前有 native/线程/外部回调的目标进程拒绝 reload。
- 拒绝时不发生旧新 hook 叠加。
- 第一版不能在 README 中写“完整支持 hot reload”。

## 3. 必须先具备的基础设施

### 3.1 HookHandle registry

所有现代 hook 都要记录 `HookHandle`，供后续：

- Java hook 卸载。
- Java hook 原子替换。
- reload 诊断。
- 重复安装检测。

### 3.2 线程状态

至少记录：

- AntiRecall native maintain thread 是否启动。
- ResignTracker boot thread 是否启动。
- ResignTracker tracker thread 是否启动。
- archive push thread 是否启动。
- ProfileCapture delayed task 是否存在。

### 3.3 外部回调状态

至少记录：

- 配置 BroadcastReceiver 是否注册。
- ArchiveSync receiver 是否注册。
- 任何 module-owned callback 是否仍然存活。

## 4. `onHotReloaded` 策略

只有 `onHotReloading()` 返回 true 的情况下才进入下一代。

第一版的 `onHotReloaded()`：

1. 读取旧 hook handle 列表。
2. 对可以安全移除的 Java-only hook 执行 unhook。
3. 对不能确认状态的 handle 采用 fail-closed，不重复添加。
4. 重新执行必要的现代入口初始化。
5. 不触碰 native 源码。

如果无法证明某一类 hook 可安全处理，应拒绝 reload，而不是尝试“尽量继续”。

## 5. 后续完整 hot reload 工作

如果未来要真正支持主进程 reload，需要额外任务：

1. 给 Java 后台线程增加停止信号。
2. 注销所有 receiver 和外部回调。
3. 清理 delayed Runnable。
4. 为 native inline hook 增加安全 teardown。
5. 清理 JNI global reference 和旧 ClassLoader 引用。
6. 重新验证 `System.load()` 后的旧库状态。
7. 在目标进程不断开的情况下验证 native hook 替换。

这不属于第一版 API 迁移的必要范围。

## 阶段出口

- `autoHotReload` 配置存在且格式正确。
- 不安全状态会明确拒绝 reload。
- 拒绝 reload 不会导致进程崩溃。
- reload 测试不会产生重复 Java hook。
- 文档明确当前是“安全门控”，不是完整 native hot reload。
