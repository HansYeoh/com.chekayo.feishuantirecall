# 阶段 3 执行记录：单一入口与生命周期分发

- 日期：2026-10-05
- 分支：`libxposed-api-102-migration`
- 基线：阶段 2（复审通过，commit `8237038`）
- 执行文档：[04-entry-lifecycle.md](../../04-entry-lifecycle.md)

## 变更清单

### 1. FeishuKitModule.java —— 唯一 modern 入口（新增）

`META-INF/xposed/java_init.list` 自阶段 1 起就指向本类，本阶段补上实现，消除「元数据指向
不存在类」的中间态。结构按 04 文档：

- `onModuleLoaded`：`ModuleRuntime.bind(this, processName)` → `ModulePath.apply(this)`。
  只做进程级初始化；bind 被拒（跨代重复）时防御性直接返回，不触碰业务。
  MODULE_PATH 回写先于一切业务 hook（AntiRecall 签名校验 / ResignTracker 抽 so 的前置条件）。
- `onPackageLoaded`：只记一条日志，不分发（04 文档 §4 防双重分发）。
- `onPackageReady`：唯一完整分发入口。顺序 = `AntiRecall.installConfigBridge()`（先于包名过滤，
  见偏差 1）→ 国内/国际包名 + `AntiRecall.isLarkApp` 白标过滤 → 幂等表去重 →
  `ModuleRuntime.setTargetPackage` → 依次 `install` 四个功能类（顺序沿用 legacy xposed_init：
  AntiRecall / ResignTracker / FuckLarkSettings / ProfileCapture）。
  幂等表 `DISPATCHED`：key = `process|package|ClassLoader identity`（04 文档 §6），
  重复回调记「skip duplicate dispatch」日志。
- `onHotReloading`：**fail-closed 返回 false** 并记原因日志——native inline hook、安装/维护线程、
  配置桥接收器均无 teardown 能力（README §2 产品决策：声明 autoHotReload=true 但安全拒绝优先）。
- `onHotReloaded`：正常不可达（reload 恒被拒），仅记诊断日志后 `super`（保留 API 默认的
  旧代 HookHandle 清理语义）。
- 不实现 `onSystemServerStarting`（04 文档 §1）。

API 签名核对方式：从 vendored AAR 的 `classes.jar` 直接 `javap`——`XposedModule` 为无参构造 +
`XposedInterfaceWrapper.attachFramework` 接线；`onModuleLoaded/onPackageLoaded/onPackageReady` 为
default void，`onHotReloading` default 返回 **false**（本阶段的显式覆写只是把「默认拒绝」变成
「有意拒绝 + 日志」）；`PackageReadyParam` 在 `PackageLoadedParam` 之上追加
`getClassLoader()` / `getAppComponentFactory()`。

### 2. 四个 legacy 入口 → 功能类（04 文档 §7 映射）

| 旧入口 | 处理 |
|---|---|
| `AntiRecall` | 删 `implements IXposedHookLoadPackage, IXposedHookZygoteInit` 与 `initZygote`；`handleLoadPackage(lpparam)` → `public static install(packageName, classLoader)`，内部功能逻辑零改动；新增 `setModulePath` 供 ModulePath 直写 |
| `ResignTracker` | 同上；**主进程限制保留在 install 内部**（`PKG.equals(currentProcessName())`） |
| `FuckLarkSettingsHook` | **整类删除**；`FuckLarkSettings` 删接口后 `handleLoadPackage` → `install(packageName, classLoader)` |
| `ProfileCapture` | 删接口，`handleLoadPackage` → `install(packageName, classLoader)` |

四个 `install` 保留各自的 `isLarkFamily + isLarkApp` 自卫过滤与 `PKG` 锁定语义
（`DataViews.PKG`、`AccountPaths.currentPkg` 等下游不动）——出口项「国内/国际/白标判断
没有被削弱」按两层理解：入口层是唯一分发闸门，功能层过滤保留使其自洽、不依赖调用方自觉。

### 3. ModulePath 反射回收（阶段 2 记录偏差 1 的预告项）

两个持有类补公开 `setModulePath` 后，`ModulePath.apply` 改为直接调用 setter，删除按字段名
反射写入（`HOLDERS` 数组、`Class.forName` 一并移除）。fail-closed 语义不变：路径不可得时
保持字段 null 并记日志。连带删除 `Reflect.setStaticObjectField`（全仓唯一使用方就是
ModulePath，避免留下无调用方的桥接 API；`Reflect.getStaticObjectField` 仍有业务用量，保留）。

### 4. 注释措辞同步

`initZygote`/`handleLoadPackage` 的说明性注释改为新入口口径（NotifArchive、AntiRecall
startNative 的异常文案等）；javadoc 中保留的 legacy 对照字样属阶段 6 rg 扫描拍板项
（阶段 2 记录遗留事项 1，非本阶段引入）。

## 偏差与理由

1. **`installConfigBridge` 从 AntiRecall.install 上移到入口，先于包名过滤执行。**
   legacy `handleLoadPackage` 对框架投递的每个包先装配置桥再过滤（LSPosed legacy 模式投递
   范围 = scope 内的包）。modern 静态 scope 投递集合相同（scope.list 两包 + 用户在 LSPosed
   勾选的白标包），先装后滤保持行为逐字节等价；配置桥自身幂等（CONFIG_BRIDGE_INSTALLED）。
2. **独立编译门形态调整。** 入口按文档要分发到业务类，业务类阶段 4 前仍依赖 stubs，
   「无 stubs classpath」不可能对全源码成立。门改为两步：全量编译得业务 .class →
   仅重编 6 个 modern 源、classpath 只有业务 .class + android.jar + classes.jar
   （stubs 完全不在 classpath）。任何 modern 源引用 de.robv 会直接编译失败，门强度不变。
3. **`onHotReloaded` 调用 `super`。** API 默认实现遍历 `getOldHookHandles()` 清理旧 handle；
   虽然正常路径不可达，保留默认语义比静默吞掉更符合 protective。

## 验证结果（Windows 本机）

### 1. 入口独立编译门 —— PASS

- 脚本：[check-entry-standalone.sh](check-entry-standalone.sh)，日志：
  [standalone-compile.log](standalone-compile.log)。
- 断言 1：modern-only 编译（classpath 无 stubs）通过；6 个 modern 类字节码 `de/robv` = 0；
  FeishuKitModule/ModuleRuntime/ModuleLog/HookRuntime/ModulePath 消费 `io/github/libxposed`，
  Reflect 零 API 依赖。
- 断言 2：`FeishuKitModule` 为 `public final class ... extends io.github.libxposed.api.XposedModule`。
- 断言 3：app 源码零 `implements IXposedHook*` / `initZygote` / `handleLoadPackage(` /
  `LoadPackageParam`；FuckLarkSettingsHook 已删；stubs 目录按计划保留至阶段 4。
- 断言 4：`java_init.list` 单行 = `com.chekayo.feishuantirecall.FeishuKitModule`；
  module.prop minApi/targetApi = 102。

### 2. 入口生命周期行为测试（宿主 JVM）—— 32/32 PASS

- 测试：[workbench/EntryBehaviorTest.java](workbench/EntryBehaviorTest.java)（伪造
  XposedInterface + attachFramework 真实 FeishuKitModule；业务 hook 走 stubs 空操作，
  断言聚焦分发层可观测状态）；脚本：[check-entry-behavior.sh](check-entry-behavior.sh)，
  日志：[behavior-test.log](behavior-test.log)。
- 覆盖：bind 接线（进程名 / api=102 日志）、ModulePath fail-closed 与正路径双写、
  onPackageLoaded 不分发、非目标包过滤、国内/国际包分发、五个 PKG 锁定、
  同 key 幂等 + 跳过日志、ClassLoader identity 参与幂等键、onHotReloading 拒绝、
  跨代 bind 拒绝。
- 测试 cwd 收在临时目录：分发链路里 Config 首次落盘按相对盘符写 `/data/data/...`
  （Windows 落到 cwd 同盘根），随脚本 trap 清理，不污染仓库。

### 3. 完整构建 —— PASS

- `./build.ps1` 全链路过（日志 [build.log.txt](build.log.txt)，中文 GBK 乱码为已知无害现象）。
- APK `feishu-antirecall.apk` 1501KB；签名证书 SHA-256 `7c20f829…` 与基线一致。
- APK 结构：`META-INF/xposed/` 三件套在根目录，`java_init.list` 内容为 FeishuKitModule，
  无 `assets/xposed_init`。

### 4. DEX 对照（业务等价性证据）—— PASS

| DEX 内字符串出现次数 | 迁移前基线 | 阶段 2 | 本阶段 | 说明 |
|---|---|---|---|---|
| `de/robv` | 9 | 9 | **5** | −4 = 删除的 lifecycle 接口描述符（IXposedHookLoadPackage / IXposedHookZygoteInit / 其 StartupParam / XC_LoadPackage$LoadPackageParam），逐项可解释 |
| `XposedBridge` / `XposedHelpers` | 1 / 1 | 1 / 1 | **1 / 1** | 业务 hook/反射面未动（阶段 4 切换） |
| `XC_MethodHook` | 3 | 3 | **3** | 同上 |
| `XC_LoadPackage` / `IXposedHook` | 1 / 3 | 1 / 3 | **0 / 0** | lifecycle 接口清零（本阶段出口项） |
| `io/github/libxposed` | 0 | 5 | **10** | +FeishuKitModule 对 API 的合法引用（定义不进 DEX 由 d8 输入结构性保证） |

### 5. 全局不变量抽查 —— PASS

- `git diff HEAD -- native/jni` 为空 ✅；`version.json` 未动 ✅；module.prop / scope.list /
  java_init.list 内容未改（实现补齐后语义一致）✅
- hook 注册点数量与位置零变化（本阶段只换分发层，未触碰任何 `findAndHookMethod`/
  `hookAll*` 调用）✅

## 遗留事项（阶段 4/6/7 处理）

1. **硬性验收「真实框架可加载」待真机。** 本阶段证据链（元数据正确 + 入口类实现 + 宿主行为
   测试）覆盖静态与分发层语义；LSPosed modern 环境实际加载 FeishuKitModule 需真机验证
   （阶段 7 真机回归的前置项），本机无法替代。
2. 122 处 `XposedBridge.log` → `ModuleLog`、40 处注册点 → `HookRuntime`、19 处非 hook 反射 →
   `Reflect` 的切换在阶段 4 逐文件执行（05 文档）；本阶段业务 hook 面零改动。
3. javadoc 对照字样（`de.robv`/`XposedBridge` 等）命中 07 文档 rg 扫描的问题沿用阶段 2
   登记，阶段 6 拍板。
