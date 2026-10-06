# 2026-10 分支代码审计修复记录

- **日期**：2026-10-06
- **输入**：分支代码审计报告（`build/audit/audit-report.txt`，审计 HEAD `69c09af`，基线 `main/544b593`）+ 审计方复核结论（Codex 会话复核，未发现误报，5 项维持 P2）
- **范围**：仅修复审计发现的 5 项 P2 问题与失效的第 2 阶段验证入口；不改 native/jni、不改版本号、不动业务行为基线
- **验证**：`check-audit-fix-behavior.sh`（本目录，新增）+ 既有第 2/3/4/5 阶段行为脚本全量重跑；全量 app 源码编译通过

## 修复清单

### F1 [P2] UI 拦截器会捕获宿主异常，再次调用原方法 —— 已修复

- `AiPeekBlock.java`（addView / setVisibility 两个 hooker）、`AntiRecall.java`（ReadReqHook 短参数路径 / InvokeHook 提前放行分支 / MapperHook 提前返回分支）
- 修复模式统一：只有模块自身的预处理逻辑（判定、打标、改参、日志）允许处于吞异常的 `try` 内；原方法放行（`chain.proceed`）与短路（`return null`）移到 `try` 之外，每条路径恰好执行一次。宿主异常原样上抛，不再被吞、不再触发第二次执行。
- 语义保持：放行/短路/改参（如 setVisibility 强制 GONE、ReadReqHook 浏览清空与回复合并）与 legacy `beforeHookedMethod` 边界一致；after-hook 形态的 hooker（setText、getAuditDependency 等）本来就先 `proceed()` 后处理，不在本问题面内，未改动。

### F2 [P2] 启动期间热重载会错误放行，并卸载已经安装的功能 —— 已修复

- 采纳审计建议的「登记不可热重载状态 + 生命周期决策与安装互斥」，并叠加「已装 Java hook 即拒绝」：
  1. `HotReloadSafety` 新增第四类资源 `JAVA_HOOK`（`markJavaHook` / `hasJavaHooks` / describe 分组 `java-hooks=[...]`），`GateSnapshot.isClean` 纳入四类判定；
  2. `HookRuntime.hook()` 在安装锁内、任何 hook 对框架可见之前登记 `installed-java-hooks`——登记与安装原子，安装线程不可能被门控漏检；
  3. `FeishuKitModule.onPackageReady` 在业务分发开始（含任意注入进程的配置桥延迟 hook）前登记 `dispatch-begun`——「分发开始过但零 hook 安装成功」的进程同样 fail-closed；
  4. `FeishuKitModule.onHotReloading` 先持 `HookRuntime.installLock()` 再取安全快照——门控判定与 hook 安装串行化，并发分发线程无法插在「查完资源到返回放行」之间装上新 hook（锁序恒为 installLock → HotReloadSafety 内部锁，无反向路径）。
- 真机启动窗口（`:wschannel` 约 1 秒的延迟绑定空档）内「已装大量 Java hook、配置桥接收器尚未注册」的进程不再被误判为干净；reload 拒绝后不存在「旧 hook 被框架卸载且新代不重分发」的功能丢失路径。
- 已知边界：与框架的换代交互仍以「框架按目标串行化 reload」为前提（阶段 5/6 文档既有结论）；本修复消除的是模块侧可观测的错误放行判定。

### F3 [P2] 固定作用域声明与白标支持冲突 —— 已修复

- `module.prop`：`staticScope=true` → `staticScope=false`（动态作用域）。`scope.list` 保持国内版 + 国际版两个默认推荐包不变；遵循框架契约，用户仍可按既有流程手动勾选企业白标应用，`AntiRecall.isLarkApp` 白标探测恢复执行机会。
- 设计文档同步：`02-build-and-metadata.md`（module.prop 建议值 + 理由）、`07-build-static-validation.md`（静态验证 checklist）。
- 边界（维持审计口径）：白标路径未做真机实测，本修复是元数据契约与产品支持范围的对齐。

### F4 [P2] 「精确」反射定位会误挂父类方法或错误重载 —— 已修复

- `Reflect.findMethodExact` 改为直接委托 `clazz.getDeclaredMethod(name, parameterTypes)`：只在目标类 declared 成员里找、按 `Class` 身份严格比较（`int` 与 `Integer` 不等价）、不沿父类上溯——与 legacy `findAndHookMethod`/`XposedHelpers.findMethodExact` 语义一致。
- 父类上溯 + 装箱等价 + 加宽匹配的 best-match 语义保留在 `callMethod`/`callStaticMethod`（`invokeBestMatch`/`matchScore`），这是 legacy `callMethod` 的既有语义，未被审计质疑。
- `findDeclaredMethods`/`findDeclaredConstructors` 的显式签名过滤参数同步改为严格 `Class` 身份比较（`paramsExact` 重写）；通配（null）/空数组语义不变，阶段 2 全部断言不受影响。
- 全部精确注册点核对：现役 19 处 `hookMethod/findAndHookMethod` 的目标方法均在指定类内 declared（View/TextView/ViewGroup/Window/SurfaceView/Instrumentation/NotificationManager/Activity 及业务类），严格化无回归。

### F5 [P2] 第二个下载审计服务实现会被误判为已安装 —— 已修复

- `FileDownloadUnlock`：新增 `auditId(prefix, class, method)`，动态发现的 hook 逻辑 ID 统一为 `前缀.声明类#方法名(形参表)`（`dlunlock.audit.*` 与 `dlunlock.svcaudit.*` 两处同改），不再依赖「方法名#序号」。
- `HookRuntime.hook()`：同 registryKey 但 Executable 不同 → 抛 `IllegalStateException("... id conflict ...")` 报冲突，不再静默复用既有记录（防未来新的 ID 配置错误重演「漏装还照常计数」）；同键同 Executable 仍幂等返回既有记录（阶段 2/4 断言保持）。
- 边界（维持审计口径）：该路径服务旧版动态服务发现多实现条件，8.1.12 真机未触发；修复的是工具层缺陷本身。

### 附：第 2 阶段验证入口失效 —— 已修复

- `records/phase-2/check-bridge-behavior.sh` 编译集由「桥接五类」扩为全量 app 源码（`ModulePath` 在阶段 3+ 新增 AntiRecall/ResignTracker 直接依赖后，原编译集失效）。原 20 项桥接层断言不变、全过。

## 同步更新的既有测试

- `records/phase-5/workbench/HotReloadBehaviorTest.java`：门控断言由三类扩为四类（§1/§13/§14）；新增 §15——真实分发后（仅 java-hooks 登记、其余三类全空的启动窗口形态）拒绝 reload、零 hook 安装成功的分发过进程拒绝 reload、清登记（模拟换代）恢复放行。

## 验证记录

- `check-audit-fix-behavior.sh`：F3 元数据 grep 断言 + F1/F2/F4/F5 行为断言（宿主 JVM，真实分发后故障注入），全部 PASS，输出见 `behavior-test.log`。
- 阶段 2/3/4/5 四个既有行为脚本全量重跑 PASS（阶段 2 使用修复后的编译集）。
- 全量 app 源码 `javac` 编译通过（`-source/-target 8 -bootclasspath android.jar`）。
- 未做：真机安装/热重载/功能矩阵重测（本修复不改变正常路径行为，真机回归留待阶段 8 前按需执行）；未重编译 native（零改动）。
