# 阶段 2 执行记录：现代运行时桥接层

- 日期：2026-10-05
- 分支：`libxposed-api-102-migration`
- 基线：阶段 1（commit `2934a28`，审计入档 `a095679`）
- 执行文档：[03-runtime-bridge.md](../../03-runtime-bridge.md)

## 变更清单

新增 5 个桥接类（`app/src/main/java/com/chekayo/feishuantirecall/`），职责不混杂、互相之间只依赖
`io.github.libxposed.api` 与 Android SDK。本阶段**只建桥接层，不切调用方**：业务代码 122 处
`XposedBridge.log`、40 处 hook 注册点、19 处非 hook 反射仍走 legacy stubs，按 README 推荐顺序在
阶段 4 逐文件切换。

### 1. ModuleRuntime.java —— 模块运行时唯一状态持有者

- `bind(XposedModule, processName)`：onModuleLoaded 时机（阶段 3 接线）绑定，首次绑定生成
  本代唯一标识（UUID）；同代重复绑定幂等，跨代绑定拒绝（hot reload 防旧代复活）。
- `setTargetPackage(packageName, classLoader)`：onPackageReady 时机更新目标包维度。
- 持有：模块实例（对外只暴露 `XposedInterface`）、进程名、包名、ClassLoader、模块 APK 路径、
  generation id。业务类不得自行缓存 XposedModule 实例（03 文档 §1）。

### 2. ModuleLog.java —— 统一日志入口

- `log(String)` 等价 `XposedBridge.log(String)`：正文不动（新旧日志可对照），固定 tag `FeishuKit`
  （与 module.prop `name` 一致），默认 `Log.INFO`。
- 未绑定（模块自身进程）或框架侧异常时安全降级为 no-op，与 legacy 在非注入进程的行为一致；
  protective：日志永不向业务抛异常。
- 附 `log(String, Throwable)` / `log(int, String, Throwable)` 变体，映射 API 102 原生
  `log(int, String, String, Throwable)` 通道。

### 3. Reflect.java —— 反射工具（替代业务在用的 XposedHelpers 子集）

- 覆盖实际用量：`findClass`（×2）/ `callMethod`（×9）/ `callStaticMethod`（×6）/
  `getStaticObjectField`（×2），另加 `setStaticObjectField`（ModulePath 写字段所需）。
- 精确查找 `findMethodExact`：名字+形参精确匹配（primitive 与包装类等价），沿父类上溯。
- 按签名枚举 `findDeclaredMethods` / `findDeclaredConstructors`：**只看本类 declared、不上溯**，
  与 legacy `hookAllMethods` / `hookAllConstructors` 语义一致（05 文档验收点），供 HookRuntime 使用。
- best-match 调用：逐实参打分（同型 0 / 装箱·加宽 1 / assignable 2 / null 配引用形参 3），
  沿父类取全局最优；`InvocationTargetException` 解包为原始原因（RuntimeException/Error 原样抛）；
  找不到成员抛带目标类/方法签名的 `NoSuchMethodError` / `NoSuchFieldError`。

### 4. HookRuntime.java —— hook 注册与登记中心

- 核心 `hook(Executable, logicalId, Hooker)`：`module.hook(target).setId(key).intercept(hooker)`；
  未绑定 ModuleRuntime 时抛 `IllegalStateException`（禁止在入口生命周期外安装）。
- 便捷入口：`hookMethod(Class, name, paramTypes, id, Hooker)`、
  `findAndHookMethod(String, ClassLoader, ...)`、`hookAllMethods`、`hookAllConstructors`。
  hookAll* 无匹配返回空表不抛异常（与 legacy 一致）。
- 登记表 `InstalledHook`：logicalId / processName / packageName / ClassLoader / Executable /
  人类可读签名 / 安装时间戳 / API `HookHandle`；提供 `unhook()` 与 `replaceHook()`（同 ID 原子替换，
  阶段 5 备用）、`snapshot()` / `getByLogicalId()` 诊断查询。
- 幂等键 = process + package + ClassLoader identity + logicalId；重复安装只告警并返回既有记录，
  业务类不得自建重复检测（03 文档 §4）。异常模式不在 per-hook 覆盖，跟随 module.prop
  `exceptionMode=protective` 单一来源。

### 5. ModulePath.java —— 模块 APK 路径桥接

- `resolve(module)`：`getModuleApplicationInfo().sourceDir`，失败返回 null 并记日志。
- `apply(path)`：写入 `AntiRecall.MODULE_PATH` 与 `ResignTracker.MODULE_PATH`（理由见偏差 1）；
  fail-closed——失败保持字段 null，业务侧既有 null 判断自行降级。必须在业务 hook 安装前完成
  （阶段 3 的 onModuleLoaded 接线）。native loader 的 APK 内路径约定不受影响。

## 偏差与理由

1. **ModulePath 经反射写入两个 MODULE_PATH 字段（03 文档 §6 只说"写入"）。**
   两字段均 package-private 且分属 `com.chekayo.feishuantirecall` / `com.chekayo.larkresign` 两个包，
   桥接层无法直接赋值；`DataViews.java:1076` 读取同字段已有反射先例，故沿用按字段名反射写入。
   阶段 3/4 若为两个持有类补公开 setter，可直接回收此反射。
2. **`hookMethod` 找不到方法抛非受检 `NoSuchMethodError`，而非 legacy 的受检 `NoSuchMethodException`。**
   现役 40 处注册点全部 `try/catch (Throwable)`，受检与否不影响现有行为；非受检让阶段 4 的
   迁移后调用点不必层层声明 throws。
3. **本阶段不创建 `FeishuKitModule`，桥接层暂无调用方。**
   入口与生命周期分发是阶段 3 产物（04 文档）；独立编译验证以
   [workbench/BridgeSmokeEntry.java](workbench/BridgeSmokeEntry.java)（仅编译不打包的最小
   XposedModule 子类）完成，见下文验证 1。
4. **Reflect 增加 03 文档未列的 `setStaticObjectField`。** ModulePath 写字段所需，语义与
   get 对称。

## 验证结果（Windows 本机）

> 以下为首轮（commit `22fe6a4`）结果。审计修复后日志文件已被重跑输出覆盖（APK 1497KB →
> 1501KB），最终数值以文末「审计修复」章节为准。

### 1. 桥接层独立编译（阶段出口硬性项）—— PASS

- 脚本：[check-bridge-standalone.sh](check-bridge-standalone.sh)，日志：
  [standalone-compile.log](standalone-compile.log)。
- classpath 只有 android.jar（android-37.0）+ vendored AAR 解出的 classes.jar（17065 bytes，
  与阶段 1 记录一致），**不含 stubs**；编译 5 桥接类 + 最小入口共 7 个类文件。
- 断言 1：产物 class 文件 grep `de/robv` = **0 命中**（字节码级，javadoc 字样不进产物）✅
- 断言 2：6 个类引用 `io/github/libxposed`（真实消费 API 102）✅
- → 出口「桥接层独立可编译、无 legacy 类型、可由最小 modern 入口编译通过」达成。

### 2. 完整构建 —— PASS

- `./build.ps1` 8 阶段全过，日志见 [build.log.txt](build.log.txt)（中文 GBK 乱码为已知无害现象）。
- 产物 `feishu-antirecall.apk` 1497KB（阶段 1 为 1493KB，+4KB 即 5 个新类）；签名证书
  SHA-256 `7c20f829…` 与基线一致。
- APK 结构：`META-INF/xposed/` 三件套在根目录、无 `assets/xposed_init`（jar tf 核对）✅

### 3. DEX 对照（业务等价性证据）—— PASS

| DEX 内字符串出现次数 | 迁移前基线 APK | 本次构建 | 说明 |
|---|---|---|---|
| `de/robv` | 9 | **9** | 业务类 legacy 引用，逐类描述符去重后一致 → 业务侧零变化 |
| `XposedBridge` / `XposedHelpers` | 1 / 1 | 1 / 1 | 同上 |
| `XC_MethodHook` | 3 | 3 | 同上 |
| `io/github/libxposed` | 0 | 5 | **桥接层对 API 的合法引用**（运行时由框架提供） |
| 5 个桥接类名 | — | 各 1 | 均已进 DEX |

- 阶段 1 的「DEX 无 io/github/libxposed」口径自此改写：阶段 2 起桥接层**引用** API 属预期，
  不变的约束是 API **定义**不进 DEX——由 d8 输入仅含 `build/app` 类文件结构性保证（classes.jar
  只进 javac classpath），不依赖 grep。
- 桥接层 javadoc 中出现的 `XposedBridge` / `XposedHelpers` / `de.robv` 字样均为对照说明
  （标注每个方法替代的 legacy 调用），非代码引用；硬性门槛由验证 1 的字节码级断言承担。

### 4. 全局不变量抽查 —— PASS

- `git diff HEAD -- native/jni` 为空 ✅；`version.json`、Manifest、`AntiRecall` 版本常量未动 ✅
- 新增文件仅 5 个桥接类 + `records/phase-2/`，无业务文件改动 ✅

## 遗留事项（阶段 4/6 处理）

1. **07 文档 §3 的 rg 扫描会命中 javadoc 对照字样。** 桥接层与既有业务注释都含
   `XposedBridge`/`XposedHelpers` 等说明性文本；阶段 6 执行扫描时需拍板：人工核对命中均为
   注释，或先批量改写注释表述。非本阶段引入（业务注释早已如此），仅提前登记。
2. 122 处 `XposedBridge.log` → `ModuleLog.log`、40 处注册点 → `HookRuntime`、19 处非 hook
   反射 → `Reflect` 的切换全部在阶段 4 按逐文件顺序执行（05 文档）。
3. `ModuleLog` 未绑定时的 no-op 语义依赖「模块自身进程不装 hook」现状；若未来模块自身进程
   需要日志，需另行走 logcat 的通道（03 文档允许安全降级，暂按现状实现）。

## 审计修复（2026-10-05，首轮审计 暂不通过 → 修复待复审）

首轮定点审计（针对 commit `22fe6a4`）判定提交范围与工作区状态无问题，但提出 1 项 P1
功能性缺陷 + 2 项非阻塞问题。以下全部修复并补行为验证。

### P1：hookAllMethods / hookAllConstructors 实际只匹配无参成员 —— 已修复

- **根因**：`HookRuntime` 以省略 varargs 的形式调用
  `Reflect.findDeclaredMethods(clazz, methodName)` / `findDeclaredConstructors(clazz)`，
  Java 此时传入的是**空数组**而非 `null`；而 `Reflect` 只把 `parameterTypes == null` 视为
  通配，`paramsExact(declared, [])` 仅在零参时成立 → 通配失效，只返回零参成员。按此实现，
  阶段 4 迁移带参目标（`onActivityResult`、带参构造函数、审计方法等）会静默漏 hook。
- **修复**（双保险，避免再次踩坑）：
  1. `Reflect` 新增**非 varargs 的显式通配重载** `findDeclaredMethods(Class, String)` 与
     `findDeclaredConstructors(Class)`（内部传 `(Class<?>[]) null`），作为 hookAll* 的规范入口；
     varargs 版 javadoc 明确标注「传 null 通配；空数组=仅零参；不要用省略 varargs 表达通配」。
  2. `HookRuntime.hookAllMethods/hookAllConstructors` 改调显式通配重载（调用点文本不变，
     重载决议自动切换到非 varargs 版）。
- **回归断言**（见下「行为验证」）：空数组与 null 两种调用形式的命中数都被固定下来，
  该 bug 若回潮会被测试直接捕获。

### 非阻塞①：unhook() 用当前上下文重算 registry key —— 已修复

- **根因**：`InstalledHook` 已保存安装时的进程/包名/ClassLoader，但 `unhook()` 仍调
  `dedupeKey(logicalId)` 按**当前** `ModuleRuntime` 上下文重算 key；目标包或 ClassLoader 已切换时
  `REGISTRY.remove` 落在错误的 key 上，框架侧 hook 卸载了、登记表记录残留，后续同 logicalId
  安装会被误判为重复。
- **修复**：`hook()` 把安装时算好的 key 存入 `InstalledHook.registryKey`（新增
  `getRegistryKey()`），`unhook()` 按该 key 移除，不受之后 `setTargetPackage` 影响。

### 非阻塞②：callStaticMethod 未筛选静态方法 —— 已修复

- `invokeBestMatch` 增加 `staticOnly` 形参：`callStaticMethod` 只在 static 方法中选择，
  同名形参兼容的实例重载同时存在时不会误选后以 `invoke(null, …)` 触发 NPE；
  `callMethod`（实例路径）保持 legacy 的宽松语义（实例/静态均可）。
- 现役 6 处 `callStaticMethod` 目标均为静态方法，行为无回归。

### 行为验证（新增，审计要求的最小行为测试）—— 20/20 PASS

- 测试：[workbench/HookBridgeBehaviorTest.java](workbench/HookBridgeBehaviorTest.java)
  （宿主 JVM 直跑，伪造 `XposedInterface`/`XposedModule`，产物不进 APK、不依赖真机）；
  运行脚本：[check-bridge-behavior.sh](check-bridge-behavior.sh)，日志：
  [behavior-test.log](behavior-test.log)。
- 覆盖面：
  - hookAll* 通配：零参/单参/多参方法、无参/带参构造函数全部覆盖；`hookAllMethods` 按名字
    全遍历总和(6) == `getDeclaredMethods().length`；`hookAllConstructors` 数量(3) ==
    `getDeclaredConstructors().length`；fake 框架侧实际安装次数与返回条数一致；
  - 空数组=仅零参 vs null=通配 的语义区分（P1 回归断言，方法与构造函数各 2 条）；
  - 重复 `hookAll*` 幂等（同 id 同维度 0 新安装）；
  - registry key 固化：切包后 unhook → 切回原包无残留记录、同 logicalId 可重新安装；
  - `callStaticMethod` 静态筛选（static dup(Object) vs instance dup(int) 同参兼容场景）与
    `callMethod` 实例路径。

### 修复后复验（全链路）

- 独立编译门（[standalone-compile.log](standalone-compile.log)）：重跑 PASS，7 类编译、
  字节码 `de/robv` = 0。
- 完整构建（[build.log.txt](build.log.txt)）：8 阶段 PASS，APK 1501KB（+4KB 为通配重载与
  登记字段增量），证书 `7c20f829…` 与基线一致。
- DEX legacy 引用计数复查：`de/robv`=9 / `XposedBridge`=1 / `XposedHelpers`=1 /
  `XC_MethodHook`=3，与迁移前基线及首轮构建完全一致（业务侧仍零变化）；
  `io/github/libxposed`=5 不变。
- 变更范围：仅 `Reflect.java`、`HookRuntime.java` 两个桥接类 + 本记录与测试工件；
  native、版本号、业务文件零改动。
