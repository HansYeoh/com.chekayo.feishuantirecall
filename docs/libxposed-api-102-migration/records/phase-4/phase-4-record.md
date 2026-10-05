# 阶段 4 执行记录：逐点迁移 hook 注册与回调

- **执行日期**：2026-10-05
- **对应文档**：[05-hook-migration.md](../../05-hook-migration.md)
- **分支**：`libxposed-api-102-migration`
- **前置状态**：阶段 3 已完成并审计通过（`e47ab44`）

## 1. 迁移范围与结果

| 项 | 迁移前（阶段 3 出口） | 迁移后（本阶段出口） |
|---|---|---|
| legacy hook 注册点 | 40（hookMethod 9 / findAndHookMethod 23 / hookAllMethods 6 / hookAllConstructors 2） | 0，全部经 `HookRuntime`（模块内 hook API 单一收口） |
| `XposedBridge.log` | 122 | 0，全部经 `ModuleLog.log`（正文逐字保留） |
| `XposedHelpers` 反射 | 19（callMethod 9 / callStaticMethod 6 / findClass 2 / getStaticObjectField 2） | 0，全部经 `Reflect` |
| 源码 `de.robv` / `XC_MethodHook` 引用 | 10 个业务文件 | 0 |
| stubs/de/robv（6 文件） | 参与编译 | **已删除**；stubs 仅剩 `java/lang/invoke/LambdaMetafactory.java`（-source 8 编译桩，按 02 文档注释保留） |
| DEX legacy 引用计数 | de/robv=5, XposedBridge=1, XposedHelpers=1, XC_MethodHook=3 | **全部 = 0**（硬性验收项：删 stubs 后重扫描通过） |
| DEX `io/github/libxposed` | 10 | 11（+1 = 业务类开始持有 Hooker/Chain 接口签名，合法消费） |

逐文件迁移顺序按 05 文档 §3 执行：AiPeekBlock → DataMigration → RestrictedModeUnlock →
FileDownloadUnlock → DownloadMirror → UpdateBanner → ProfileCapture → FuckLarkSettings →
AntiRecall → ResignTracker。每完成一个文件即跑
`rg -n 'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook' <file>`（全部零命中后才进入下一个）。

## 2. 转换模式（与 05 文档 §1 一一对应）

| legacy 模式 | modern 实现 | 等价性说明 |
|---|---|---|
| before + `setResult(x)` 短路 | `return x;`（不调 proceed） | 原方法不执行；void→null、boolean→Boolean.FALSE |
| before + 改 `p.args[i]` 后放行 | `Object[] a = chain.getArgs().toArray(); a[i]=…; return chain.proceed(a);` | getArgs 不可变列表 → 副本改参（05 §1.3） |
| before + 原地改对象属性（setAttributes 的 lp.flags） | 直接改对象后 `chain.proceed()` | 引用未替换，proceed 原参即拿到已改对象 |
| after 读 args/thisObject、不改返回值 | `Object r = chain.proceed(); …; return r;` | 原方法先执行；回调异常吞掉不影响返回值（protective 语义与 legacy 一致） |
| after `getResult()` 动态发现再注册 | `Object impl = chain.proceed();` 后对 impl 的类装 hook | FileDownloadUnlock ×2、AntiRecall 延迟装 DownloadMirror |
| 构造器 before 改参（ReadReqHook） | 同改参模式；hookAllConstructors 由 `HookRuntime.hookAllConstructors` 通配 | 构造器体仍执行，仅参数被替换；行为测试覆盖浏览清空/回复回填两路径 |
| constructor `setResult(null)`（InvokeHook 死分支，ANTIREAD_DROP=false 门控） | `return null;` 短路 | 分支编译期恒 false，仅作语义迁移，不改变任何运行时行为 |
| `p.thisObject` | `chain.getThisObject()` | TextView 重入防抖（BUSY/RECALL_UI_BUSY 弱集合）原样保留 |

回调内部 try/catch(Throwable) 的结构逐字保留；legacy 依赖框架 protective 兜底的少数回调
（如 dlmirror.deferAppCreate 无内部 try）在 modern 侧同样不额外包 try，异常路径由
module.prop `exceptionMode=protective` 单一来源兜底（阶段 1 已定，HookRuntime 不做 per-hook 覆盖）。

## 3. 关键决策与偏差

1. **死代码 `installAntiRead` + `InvokeHook` 原样迁移，不删**（AntiRecall 旧版防已读 v1，从未被调用）。
   05 文档 §2 第四组把 `Sdk.invoke*` 与「ANTIREAD_DROP 行为不变」列入迁移清单，据此按行为等价
   原则机械转换；运行时行为零变化（仍无调用方）。若审计倾向删除，可在阶段 5/6 单独收口。
2. **桥接层 javadoc 中的 legacy 类名字样改写**（HookRuntime/ModuleLog/Reflect/FeishuKitModule，
   共 16 处，全部是注释、无代码影响）。这是阶段 2/3 遗留的「阶段 6 前拍板」项，本阶段提前关闭：
   05 文档阶段出口要求「源码无 XC_MethodHook、XposedBridge、XposedHelpers」，且 07 文档 §3 的
   rg 扫描范围含 `app` 目录。改写只动注释措辞（如「等价 XposedBridge.hookAllMethods」→
   「等价 legacy 的 hookAllMethods」），语义不变。
3. **`RestrictedModeUnlock` 循环注册共享回调**：②③⑥ 的循环体在 legacy 里各自 `new XC_MethodHook()`
   （语义相同的匿名类 ×N），modern 侧收敛为三个 package-private 静态 `Hooker` 常量
   （FALSE_GATE / VOID_GATE_RESTRICT / VOID_GATE_NOAUDIT），循环内按 `#<index>` 发 logicalId。
   行为逐点等价（回调体逐字对照），并减少了重复对象；**dedupe 语义不变**——幂等键仍按
   process+package+ClassLoader+logicalId 逐方法独立。
4. **logicalId 命名约定**：`<功能>.<目标>.<序号/重载>`（如 `aipeek.addView.1`、
   `restricted.writeData#3`、`antiread2.sendreq.<全类名>`），全表见
   [hook-inventory.tsv](../phase-0/hook-inventory.tsv) 第 12 列（本阶段新增，40/40 标记 done）。
5. 构建脚本零改动：build.ps1/build.sh 的 stubs 编译步骤为递归查找，删除 stubs/de 后自动只编译
   LambdaMetafactory，无需修改（02 文档注释中的预案「清理 legacy stubs 时必须保留它」兑现）。

## 4. 出口验证（证据全部归档于本目录）

| 验证 | 结果 | 证据 |
|---|---|---|
| 独立编译门（全量源码，classpath 仅 android.jar + classes.jar，无任何 stubs） | PASS（6 断言） | `standalone-compile.log` / `check-hook-standalone.sh` |
| 宿主行为测试（迁移后回调语义，fake Chain/框架） | **24/24 PASS** | `behavior-test.log` / `check-hook-behavior.sh` + `workbench/HookMigrationBehaviorTest.java` |
| 阶段 3 入口生命周期回归（全量编译 32 断言） | PASS | `phase3-regression.log` |
| build.ps1 全链路 | PASS，APK 1501KB，证书 `7c20f829…` 与基线一致 | `build.log.txt` |
| DEX legacy 计数（对照阶段 0 基线 9/1/1/3） | **de/robv=0, XposedBridge=0, XposedHelpers=0, XC_MethodHook=0, XC_LoadPackage=0, IXposedHook=0, xposed_init=0** | 本记录 §1（`grep -ac` 实测） |
| APK 结构 | classes.dex / 双 so / META-INF/xposed 三件套在位，无 xposed_init | 本记录 §1 |
| native/jni + assets + version.json | 零 diff | `git status` 实测 |
| hook-inventory.tsv | 40/40 行标记 done（新增第 12 列） | `../phase-0/hook-inventory.tsv` |

行为测试覆盖重点（对应 05 文档 §2 第四组验收）：
浏览路径 message_ids/fold_ids 清空并按会话暂存、回复窗口暂存合并回填且 fold_ids 不动、
开关关原参透传、短参构造安全放行、InvokeHook 现状不短路、MapperHook 缓存/回填/setStatus(NORMAL)、
保密模式门禁短路（false/null）与放行（proceed 透传）、HookRuntime 幂等注册。

阶段 2 的 `check-bridge-standalone.sh`/`check-bridge-behavior.sh` 未复跑：其编译集合（桥接层子集）
不含阶段 3 改为直写的 ModulePath 依赖（AntiRecall/ResignTracker），该两脚本属阶段 2 历史产物；
全量编译与行为验证由本阶段两脚本 + 阶段 3 行为测试回归覆盖。

## 5. 遗留与下一步

1. **真机可加载性**仍属阶段 7 硬性验收（本阶段所有验证限于宿主 JVM + 本机静态）。
2. 阶段 5（hot reload 安全策略）：HookRuntime 的 `InstalledHook.unhook/replaceHook` 能力已备，
   本阶段未触碰 `onHotReloading` fail-closed 逻辑（阶段 3 已定）。
3. 阶段 6（07 文档）执行静态验证时，§3 扫描应零命中（本阶段已按其全模式预扫通过）。
