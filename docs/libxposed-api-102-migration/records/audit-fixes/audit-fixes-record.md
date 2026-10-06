# 2026-10 分支代码审计修复记录

- **日期**：2026-10-06（第一轮 `fae6bcb`；第二轮复审后收紧 F2 并修 P3 测试缺陷）
- **输入**：分支代码审计报告（`build/audit/audit-report.txt`，审计 HEAD `69c09af`，基线 `main/544b593`）+ 审计方复核结论（第一轮：无误报，5 项维持 P2；第二轮复审 `fae6bcb`：F1/F3/F4/F5 与第 2 阶段入口修复通过，F2 仍存一个 P2 并发问题，另有一个 P3 测试假通过）
- **范围**：仅修复审计发现的问题与失效的第 2 阶段验证入口；不改 native/jni、不改版本号、不动业务行为基线
- **验证**：`check-audit-fix-behavior.sh`（本目录）+ 既有第 2/3/4/5 阶段行为脚本全量重跑；全量 app 源码编译通过

## 修复清单

### F1 [P2] UI 拦截器会捕获宿主异常，再次调用原方法 —— 已修复

- `AiPeekBlock.java`（addView / setVisibility 两个 hooker）、`AntiRecall.java`（ReadReqHook 短参数路径 / InvokeHook 提前放行分支 / MapperHook 提前返回分支）
- 修复模式统一：只有模块自身的预处理逻辑（判定、打标、改参、日志）允许处于吞异常的 `try` 内；原方法放行（`chain.proceed`）与短路（`return null`）移到 `try` 之外，每条路径恰好执行一次。宿主异常原样上抛，不再被吞、不再触发第二次执行。
- 语义保持：放行/短路/改参（如 setVisibility 强制 GONE、ReadReqHook 浏览清空与回复合并）与 legacy `beforeHookedMethod` 边界一致；after-hook 形态的 hooker（setText、getAuditDependency 等）本来就先 `proceed()` 后处理，不在本问题面内，未改动。

### F2 [P2] 启动期间热重载会错误放行，并卸载已经安装的功能 —— 已修复（两轮）

**第一轮（`fae6bcb`）**：按审计建议登记不可热重载状态并令门控与安装互斥——HotReloadSafety 新增第四类资源 `JAVA_HOOK`；`HookRuntime.hook` 在安装锁内、hook 对框架可见之前登记 `installed-java-hooks`；`onPackageReady` 分发开始前登记 `dispatch-begun`；`onHotReloading` 持 `HookRuntime.installLock()` 再取快照。关闭了「已装 hook 后请求 reload 被误判干净」的场景。

**第二轮（本提交，复审 P2 推翻放行路径）**：复审并发夹具证明第一版仍可被穿越——`onHotReloading` 持安装锁取得干净快照后，另一线程可执行真实 `onPackageReady`（`dispatch-begun` 只持 HotReloadSafety 内部锁，先登记、再阻塞于安装锁），门控仍用旧快照返回 `true`；且返回 `true` 后不存在「本代退役、禁止继续分发/安装」的状态，**仅把登记移进安装锁也无法覆盖**（旧代会在换代窗口内继续安装，或被卸载不重装、或跨代残留）。夹具输出：返回前 `dispatchMarked=true, installedHooks=0`；返回后 `reloadAccepted=true, installedHooks=19`。

第二轮修复：**`onHotReloading` 无条件拒绝（统一 fail-closed）**。理由：

- 放行决策是点时的，单次快照在结构上无法覆盖「决策之后、换代完成之前」的并发安装；彻底关闭需要完整的旧代停止安装 + 新代接管协议，不是 reload 场景的当下需求。
- 真机现网任何已注入进程都至少持有配置桥接收器或其延迟 hook（阶段 7 记录：allow 路径真机不可达），统一拒绝不损失现网能力。
- `module.prop` 的 `autoHotReload=true` 维持不变（README §2 既有口径：声明能力但安全拒绝优先，不宣称完整 reload 支持）；`HotReloadSafety` 四类状态表保留作拒绝日志的 describe 诊断与未来退役实现的资源盘点；`onHotReloaded` 入口接线保留（防御性契约完整，正常情况下不可达）。

同步改动：`HookRuntime.installLock()` 访问器删除（门控不再消费）；`dispatch-begun`/`installed-java-hooks` 登记保留（诊断语义）；06-hot-reload.md §2 增补两轮收紧注记（第一版设计原文保留追溯）。

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

- `records/phase-5/workbench/HotReloadBehaviorTest.java`：状态表断言由三类扩为四类（§1/§13/§14）；§15——真实分发后（仅 java-hooks 登记、其余三类全空的启动窗口形态）拒绝 reload、零 hook 安装成功的分发过进程拒绝 reload、清登记（模拟换代）后仍拒绝。第二轮起全部「干净放行」断言改为「统一拒绝」（§2/§3 计数/§7/§14 并发 8 次全拒/收尾），状态表断言保留（诊断语义）。
- `records/phase-3/workbench/EntryBehaviorTest.java`：§9 同步——「状态表清空后放行」改为「清空后仍无条件拒绝、不向新一代传任何状态」。

## 第二轮复审修复（P3）

- **复审 P3：`AuditFixBehaviorTest` 的「零 hook 安装成功」用例假通过**——只清了 HotReloadSafety、没重置 `ModuleRuntime`，第二个模块 `bind` 被拒，分发实际仍走第一个模块的（成功安装型）框架，12 个 hook 装进了旧框架，抛错框架从未被调用。修复：用例前完整重置运行时（`resetRuntimeForNewGeneration`，与 phase-5 用例同法），并新增断言「抛错框架真实收到安装尝试（`installAttempts > 0`）且零安装成功（`hooks.isEmpty()`）」。修复后实测 attempts=12，与复审复现数吻合。

## 第二轮复审结论（2026-10-06，reaudit-ff3245e）

**复审通过，本轮审计修复正式关闭，允许进入阶段 8。** 复审确认：

- F2：`onHotReloading` 无条件返回 false，原放行竞争路径已消除。
- P3：测试运行时隔离生效，抛错框架真实收到 12 次安装尝试、零安装成功。
- 独立重跑 5 套行为脚本全部 exit 0；并发门控 2000 次全部拒绝、零异常。
- 工作区干净，`diff --check` 通过；native/jni、version.json、Manifest 相对 69c09af 均无改动。

非阻塞备注（HotReloadSafety 类头残留「按快照放行」旧表述）已当场清理：HotReloadSafety 与 FeishuKitModule.onHotReloaded 的 javadoc 改为诊断表/防御入口口径。本轮通过范围=代码与 JVM 回归审计；**阶段 8 仍需对新产物执行 build.ps1 构建、打包元数据核验与必要真机回归（阶段 7 的旧 APK 证据不能替代新产物验证）**。

## 验证记录

- `check-audit-fix-behavior.sh`：F3 元数据 grep 断言 + F1/F2/F4/F5 行为断言（宿主 JVM，真实分发后故障注入），全部 PASS，输出见 `behavior-test.log`（含第二轮统一拒绝三形态与 P3 隔离断言）。
- 阶段 2/3/4/5 四个既有行为脚本全量重跑 PASS（阶段 2 使用修复后的编译集；阶段 3/5 的门控断言按第二轮统一拒绝语义更新）。
- 全量 app 源码 `javac` 编译通过（`-source/-target 8 -bootclasspath android.jar`）。
- 未做：真机安装/热重载/功能矩阵重测（本修复不改变正常路径行为，真机回归留待阶段 8 前按需执行）；未重编译 native（零改动）；未重建 APK（build.ps1 会清空 build/ 审计现场，module.prop 为数据文件无构建耦合，留阶段 8 构建时验证）。
