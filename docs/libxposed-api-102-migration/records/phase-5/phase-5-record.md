# 阶段 5 执行记录：hot reload 安全策略

- **执行日期**：2026-10-05
- **对应文档**：[06-hot-reload.md](../../06-hot-reload.md)
- **分支**：`libxposed-api-102-migration`
- **前置状态**：阶段 4 已完成并审计通过（`2751a6d` 实施 / `2603560` 入档）

## 1. 变更范围与结果

| 项 | 迁移前（阶段 4 出口） | 迁移后（本阶段出口） |
|---|---|---|
| `onHotReloading` | 无条件 fail-closed 返回 false（阶段 3 落地） | 按 `HotReloadSafety` 状态门控：三类 teardown-unsafe 资源任一存在即拒绝并附资源清单，全部干净才放行（06 文档 §2） |
| `onHotReloaded` | 仅诊断 + super（阶段 3 时点判定为「正常不可达」） | 新一代唯一生命周期入口：bind → 记录并全量 unhook 旧 handle（框架默认）→ 补 `ModulePath`；不做业务分发（06 文档 §4） |
| 资源状态登记 | 无 | 新增 `HotReloadSafety`：native inline hook ×2、模块线程 ×4、外部回调 ×2、延时任务计数 ×1（06 文档 §3.1/§3.2/§3.3） |
| `module.prop` | `autoHotReload=true`（阶段 1 已声明） | 不变（出口项 1：声明在位、格式正确，由独立编译门 E 断言） |
| `ModuleRuntime` / `HookRuntime` / `ModulePath` / `ModuleLog` | — | **零改动**（阶段 2/3 的跨代 bind 拒绝、registry、setter 直写路径全部保持） |
| DEX legacy 计数 | 全 0 | 保持全 0；`io/github/libxposed` = 11 与阶段 4 持平 |

### 资源登记点（06 文档 §3 最小清单 + 2 处保守补充）

| 资源 | 类别 | 登记点 | 语义 |
|---|---|---|---|
| `antirecall.native-inline` | native | `AntiRecall.startNative` `System.load` 返回后 | 闩；sqlite3_step + liblark .text 维护 hook 无 unhook/dlclose |
| `antirecall.installer-thread` | 线程 | `AntiRecall.startNative` 线程 start 前 | 闩；安装轮询→安装成功转入 `nativeMaintain` 常驻循环（06 §3.2「maintain thread」） |
| `resigntracker.native-inline` | native | `ResignTracker.start` `System.load` 返回后 | 闩；sqlite3_key_v2 句柄抓取 |
| `resigntracker.boot-thread` | 线程 | `ResignTracker.install` start 前 | 闩（06 §3.2「boot thread 是否启动」） |
| `resigntracker.tracker-thread` | 线程 | `ResignTracker.start` start 前 | 闩；`while(true)` 轮询常驻 |
| `resigntracker.archive-push-thread` | 线程 | `ResignTracker.start` start 前 | 闩（06 §3.2「archive push thread」；一次性任务按文档口径记「已启动」） |
| `config-bridge.receiver(sync,pull)` | 回调 | `AntiRecall.bindConfigBridge` 注册成功后 | 闩；同一接收器承载 ACTION_SYNC + 档案 ACTION_PULL（即 06 §3.3 的配置桥与 ArchiveSync receiver） |
| `download-mirror.file-observer` | 回调 | `DownloadMirror.startObserver` startWatching 后 | 闩；保守补充（06 §3.3「任何 module-owned callback」，常驻 FileObserver 无 stopWatching 生命周期） |
| `profilecapture.scrape-delayed` | 延时任务 | `ProfileCapture` 每次 `postDelayed` 前 begin，任务体 finally end | 计数（06 §3.2「delayed task 是否存在」按未完成算） |

注：`AntiRecall` 的 `fucklark-dl-mirror` 一次性复制线程、`ToastRunnable` 主线程 Toast 等瞬态任务不登记——它们要么被上述常驻登记覆盖（FileObserver 已在），要么生命周期短于协商窗口；避免「已结束的线程仍永久阻断 reload」的失真登记。DataMigration/DataViews/UpdateBanner/OrgWalkerService 的线程只存在于模块自身进程（无 Xposed 生命周期、无 reload 协商），不属门控范围。

## 2. 关键决策与依据

1. **门控以 API 102 权威契约为准**（vendored 同版本 `io.github.libxposed:api:102.0.0` 的
   sources.jar，SHA-256 `c4a5761c…20444e` 与 Maven Central 官方 `.sha256` 一致，仅作阅读、
   不改阶段 1 的 vendored 构建决策）：
   - `onModuleLoaded`/包回调 **不会** 为 reload 新代重放，`onHotReloaded(HotReloadedParam)`
     是新代唯一生命周期入口 → bind/ModulePath 必须移到该回调；
   - `HotReloadedParam.getOldHookHandles()` 由框架提供旧代 handle 列表，默认实现即全量
     unhook → 06 文档 §4.1/§4.2 直接落在框架默认语义上；
   - `HotReloadingParam.setSavedInstanceState()` 只接受 classloader-neutral 值 → 放行时传
     字符串标记 `feishukit:generation-clean`（诊断用；放行的权威是「框架只在返回 true 后
     换代」这一契约，不依赖该标记做安全判断）；
   - 返回 true 即声明旧代已可退休，native 线程/hook/JNI 引用未清理时换代属模块 bug
     → 三类资源存在必须拒绝（06 文档 §1 的判断逐条成立）。
2. **新代 static 全新（新 ClassLoader），无需跨代共享状态**：`ModuleRuntime`/`HookRuntime`/
   `HotReloadSafety` 每代各一份，阶段 2 的「同 loader 跨代 bind 拒绝」防御保持原样且行为
   测试继续通过；新代 bind 正常必成，bind 失败分支（同 loader 双实例异常）fail-closed：
   仅 super 清旧 handle，不做任何初始化。
3. **业务分发不在 `onHotReloaded` 执行**（对 06 文档 §4.4 的落地口径）：reload 后才加载的
   包照常触发新代 `onPackageReady` 完整分发（行为测试 §10 验证）；而「reload 得以发生」
   蕴含旧代零登记——现网所有已分发进程都持有配置桥接收器（阶段 3 偏差：installConfigBridge
   先于包名过滤），故放行只可能发生在尚未分发的注入进程，不存在「旧代已装 hook 需要
   补装/替换」的可达状态。若未来为线程/接收器补上 teardown（06 文档 §5），此口径需重估。
4. **登记是元数据不是行为，门控判定走单次锁内快照**：所有埋点只写状态表，不触碰任何业务
   路径。`onHotReloading` 的判定经 `inspectReloadSafety()` 在同一次加锁内生成三类布尔与
   原因文本（不可变 `GateSnapshot`），登记线程无法插在「查完一类到返回」之间；快照发出后
   不受后续登记影响（点时语义）。理论残余窗口：快照判净之后、返回 true 之前落下的登记不
   改变本次放行——决策时点该资源尚不存在，且框架按目标串行化 reload 协商、登记只发生在
   业务安装路径，与协商无并发场景。（阶段 5 审计 P1 修复：初版为三个 has* 查询各自加锁、
   合取判定无原子窗口，见 §6。）
5. **不宣称完整 reload**：README/PR 文案保持「autoHotReload=true + 安全门控拒绝优先」；
   真实 LSPosed modern 环境的 reload 协商回归按计划属阶段 7 真机验收。

## 3. 契约演进的回归影响（偏差记录）

阶段 3 行为测试第 9 节原断言「无条件拒绝」为阶段 5 有意演进的契约，已同步改写
（`records/phase-3/workbench/EntryBehaviorTest.java`，断言数 32→35）：

- 原「onHotReloading 返回 false（拒绝）」→ 拆为「持有外部回调时拒绝 + 拒绝不写 savedState」
  与「无资源时放行 + 放行传 classloader-neutral 门控结论」；
- 原「onHotReloaded 正常路径不可达，仅诊断」→ 「onHotReloaded 接线：bind + 旧 handle 清理 +
  ModulePath」（同 loader 下 bind 幂等；真机新代为新 ClassLoader，bind 必成）；
- 第 10 节「同 loader 跨代 bind 拒绝」不受影响，保持原断言。

阶段 4 行为测试（24 断言）与阶段 4 独立编译门未受影响，复跑全 PASS。

## 4. 出口验证（证据全部归档于本目录）

| 验证 | 结果 | 证据 |
|---|---|---|
| 独立编译门（7 断言：A 无 stubs 全量编译 / B 字节码零 legacy token / C 源码 07§3 零命中 / D stubs 不回潮 / E module.prop autoHotReload=true 且 102/102+protective 未动 / F 入口走 inspectReloadSafety 单次快照且禁止分次 has* 判定 + 四类资源点打标齐全 + HotReloadSafety 纯 java + resetForTest 无生产调用方 / G java_init.list 单入口） | PASS | `standalone-compile.log` / `check-hotreload-standalone.sh` |
| 宿主行为测试（fake 框架：三类门控、闩幂等、延时任务计数归零、放行传门控结论、新代入口 bind+旧 handle 恰好各 unhook 一次+ModulePath+不分发、reload 后新包正常分发、bind 失败 fail-closed、同 loader 跨代拒绝保持；审计修复后新增：GateSnapshot 点时不变性与自洽、双写线程 4 万次 begin/end 压力下 2 万次快照零穿越+发布屏障 500 次零漏检+周期性入口门控决策与日志一致） | **49/49 PASS** | `behavior-test.log` / `check-hotreload-behavior.sh` + `workbench/HotReloadBehaviorTest.java` |
| 阶段 3 入口生命周期回归（契约演进后 35 断言） | PASS | `phase3-regression.log` |
| 阶段 4 行为测试 24 断言 + 独立编译门回归 | PASS | `phase4-regression.log` |
| build.ps1 全链路 | PASS，APK 1501KB，证书 `7c20f829…` 与基线一致 | `build.log.txt` |
| DEX legacy 计数 | de/robv=0, XposedBridge=0, XposedHelpers=0, XC_MethodHook=0, XC_LoadPackage=0, IXposedHook=0, xposed_init=0；`io/github/libxposed`=11 与阶段 4 持平；`HotReloadSafety`=5、`GateSnapshot`=2、`inspectReloadSafety`=1 处常量池引用 | 本记录 §1/§4（`grep -a -o \| wc -l` 实测） |
| APK 结构 | classes.dex / 双 so / META-INF/xposed 三件套在位，无 xposed_init；minSdkVersion 29 / targetSdkVersion 34 | 本记录 §4 |
| native/jni + assets + version.json + build.ps1/build.sh | 零 diff | `git status`/`git diff` 实测 |

## 5. 遗留与下一步

1. **真实 reload 协商回归属阶段 7**：本阶段验证限于宿主 JVM 行为测试 + 本机静态/构建；
   LSPosed modern 环境下「不安全进程拒绝且不崩溃」「拒绝后原功能不受影响」需真机确认。
2. 阶段 6（07 文档）本机验证：AAR 指纹校验、双脚本、APK 结构验证；本阶段两门已按其口径预扫。
3. 完整 hot reload（线程停止信号、receiver 注销、native teardown、06 文档 §5 七项）不在
   本次迁移范围；门控资源清单即未来的 teardown 检查表。

## 6. 审计轮次

### 第一轮：暂不通过（P1 门控判定非原子）

审计认定（其余项无阻塞：登记覆盖与 fail-closed 方向、onHotReloaded 流程、无重复分发、
全部验证证据、冻结边界均通过）：

- **P1**：入口 `onHotReloading` 分别调用三个 `has*()`，各自加锁、各自释放——登记线程可以
  插在两类查询之间（如 hasNativeHooks 为假 → 登记 → hasExternalCallbacks 仍为假），
  导致「资源已存在却放行」。与记录原表述「判定到返回之间不会被并发打标穿越」不符
  （原实现只保证单项查询原子，未覆盖三类合取判定的完整窗口）。既有 40 断言未覆盖该
  并发窗口。

### 修复（本提交）

- `HotReloadSafety` 新增 `inspectReloadSafety()`：在同一次加锁内完成三类判定与原因文本
  生成，返回不可变 `GateSnapshot`（点时快照：发出后不受后续登记影响）；
- `FeishuKitModule.onHotReloading` 只调用一次快照，拒绝原因取自同一快照的 `describe()`；
- 三个 `has*` 保留为诊断/测试用途（javadoc 标注），独立编译门 F 断言改为：入口必须走
  `inspectReloadSafety` 且**禁止**再次出现分次 `has*` 判定（防回潮）；
- 行为测试新增 §13（快照点时不变性、isClean ⇔ 三类布尔 ⇔ describe 自洽）与 §14
  （双写线程各 2 万次 begin/end 与主线程 2 万次快照并发：零穿越 + 结束后归零 +
  周期性入口门控 8 次决策与日志一致 + 发布屏障后 500 次判定零漏检 + 清理后恢复放行），
  40 → 49 断言；
- 记录 §2.4 原表述按修复后实际语义改写（含残余窗口的界定：快照判净后、返回前落下的
  登记不改本次放行——决策时点资源尚不存在，且框架按目标串行化协商、登记只在业务安装
  路径发生）。

### 修复后复验（全部 PASS）

独立编译门 7 断言（F 为快照新口径）；行为测试 49/49；阶段 3 回归 35 断言；
阶段 4 行为 24 断言 + 独立门回归；build.ps1 全链路（1501KB，证书 `7c20f829…` 与基线一致）；
DEX 复扫 legacy 全 0、`io/github/libxposed`=11 持平；保护边界零 diff。日志已刷新归档。
