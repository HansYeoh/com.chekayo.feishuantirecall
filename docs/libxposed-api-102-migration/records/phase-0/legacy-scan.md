# 阶段 0 记录 B：legacy 依赖清单与 hook 清单

- **日期**：2026-10-05
- **扫描基线**：commit `e61b868`（分支 `libxposed-api-102-migration`）
- **工具**：ripgrep，命令与阶段 0 文档第 3 节一致；原始输出存于 `raw/`（5 个文件，已入库）

## 1. 机械扫描结果（与计划文档口径对照）

| 口径 | 计划文档估算 | 本次实测 | 差异说明 |
|---|---|---|---|
| findAndHookMethod | 23 | 23 | 一致 |
| hookMethod | 9 | 9 | 一致 |
| hookAllMethods | 6 | 6 | 一致 |
| hookAllConstructors | 2 | 2 | 一致 |
| 注册点合计 | "人工统计约 35" | **40** | 以逐项清单为准（见 hook-inventory.tsv） |
| 回调类（匿名+具名） | "约 38" | **35** | 以逐项清单为准 |
| XposedBridge.log | 122 | 122 | 一致 |

计划文档第 4 节明确：总数不作为唯一验收条件，以逐项清单为准。

原始扫描输出：
- `raw/scan-all-legacy-refs.txt` —— 全仓 legacy 关键词（de.robv / XposedBridge / XposedHelpers / XC_MethodHook / IXposedHook / xposed_init），258 行
- `raw/scan-registrations.txt` —— 4 类 hook 注册 API，40 行
- `raw/scan-callbacks.txt` —— 回调类与 before/after 方法，70 行
- `raw/scan-xbridge-log.txt` —— XposedBridge.log，122 行
- `raw/scan-xhelpers.txt` —— XposedHelpers.*，41 行

## 2. 逐 hook 迁移清单

见同目录 **`hook-inventory.tsv`**（tab 分隔，11 列，40 行数据），按计划文档第 4 节的列结构建立。
每行 = 源码中 1 个注册语句；行内循环（hookAllMethods×6、循环注册×5 等）已在 notes 标注实际注册数量。

> 存放位置与计划文档第 4 节建议的 `build/migration/hook-inventory.tsv` 不同：`build/` 已被 .gitignore 忽略、且 build.ps1 每次构建开头会整目录清空，工作文件放那里既不能审计也留不住。清单正本随本记录目录入库，阶段 1 起逐项在"迁移状态"列打勾即可（如需运行列，可另加）。

### 分布概览

| 文件 | 注册点 | before | after | 改参 | 短路(setResult@before) | 动态 hook |
|---|---|---|---|---|---|---|
| AntiRecall.java | 10 | 6 | 4 | 2 | 0（+1 死代码） | 1 |
| AiPeekBlock.java | 7 | 5 | 2 | 1 | 4 | 0 |
| RestrictedModeUnlock.java | 10 | 10 | 0 | 4 | 6 | 0 |
| FileDownloadUnlock.java | 7 | 5 | 2 | 0 | 5 | 2 |
| FuckLarkSettings.java | 2 | 0 | 2 | 0 | 0 | 0 |
| DataMigration.java | 1 | 0 | 1 | 0 | 0 | 0 |
| DownloadMirror.java | 1 | 1 | 0 | 0 | 1 | 0 |
| ProfileCapture.java | 1 | 0 | 1 | 0 | 0 | 0 |
| UpdateBanner.java | 1 | 0 | 1 | 0 | 0 | 0 |
| **合计** | **40** | **27** | **13** | **7** | **16** | **3**（另有 2 个动态注册点，见下） |

mixed（同一回调既有 before 又有 after）= 0；after-hook 中修改返回值（setResult@after）= 0 —— 计划 README 中 P1"after-hook 丢失 setResult"风险在本仓库不存在。

### 高风险点标注（对计划文档第 4 节清单的逐项回答）

- **直接写 `p.args[...]`（7 处）**：AiPeekBlock:83（args[0]=GONE）、AntiRecall:471（args[0]=null 丢水印）、AntiRecall:718（args[0]/args[7] 整体替换）、RestrictedModeUnlock:178/186/194/205（FLAG_SECURE 剥离；194 为原地改对象内部字段，不替换引用）。→ 全部需转 `intercept.before(proceed(newArgs))` 模式。
- **`setResult` 短路（16 处）**：见上表；其中 DownloadMirror:56 短路同时改写返回文案。→ 转为 before-return（不调 proceed）。
- **after-hook 用 `getResult()`（2 处）**：FileDownloadUnlock:83、136 —— 均为"拿返回值发现实现类再动态挂 hook"的模式，不是改返回值。→ 对应 interceptor 的 after 读取 `result`。
- **动态 hook（注册发生在另一 hook 的回调内，3 个源 + 2 个注册点）**：
  - AntiRecall:301（after 回调内调 DownloadMirror.install 注册 mustacheFormat hook）
  - FileDownloadUnlock:83→97、136→159（after 回调内对运行时发现的类注册 hook）
  - → API 102 下同进程重复安装防护（initZygote 后 INSTALLED 门控已存在）之外，需确认 interceptor 内 hook 安装的行为等价。
- **constructor hook（2 处）**：AntiRecall:718（UpdateMessagesMeReadRequest 全构造）、721（2 个发送请求类全构造）。→ hookAllConstructors 语义映射。
- **Android framework 类 hook（19 行注册点）**：ViewGroup/View/TextView/Instrumentation/NotificationManager/Activity/Window/SurfaceView 的公开方法。→ framework 类不走目标 ClassLoader，注意 `findClass`/`hookAllMethods` 桥接实现对 bootstrap 类的处理。
- **依赖 `MODULE_PATH` 的 native loader**：AntiRecall.startNative（从 MODULE_PATH APK 抽 libantirecall.so 并 System.load）、AntiRecall.checkSignature、ResignTracker.extractSo（libresign.so）。ResignTracker 无 Java hook 但同为 legacy 入口。→ 阶段 3 的 MODULE_PATH 初始化必须先于 handleLoadPackage 等价逻辑。
- **重入风险（2 处）**：AntiRecall:417/421 与 AiPeekBlock:133/137 的 after 回调内调用 `tv.setText(...)` 会重入同一 hook 点，现有 BUSY/RECALL_UI_BUSY 弱集合防重入必须原样保留。
- **死代码（1 处）**：AntiRecall.installAntiRead / InvokeHook（:671，v1 防已读）从未被调用；其 setResult 被编译期常量 `ANTIREAD_DROP=false` 门控。迁移时建议删除并单独提交说明，如保留则按清单等价迁移。

## 3. 非 hook 的 legacy API 使用（迁移也要覆盖）

| API | 处数 | 用途 |
|---|---|---|
| `XposedHelpers.callMethod` | 9 | Command.getValue / message 的 get/set 反射调用 |
| `XposedHelpers.callStaticMethod` | 6 | AndroidAppHelper.currentApplication / ActivityThread.currentApplication |
| `XposedHelpers.getStaticObjectField` | 2 | Message$Status.NORMAL、DownloadCheckResult.ALLOW |
| `XposedHelpers.findClass` | 2 | STATUS 状态类、SDK 类（均在死代码路径） |
| `XposedBridge.log` | 122 | 全部诊断日志（tag 风格 `[fucklark]`/`[antirecall]`/`[antiread]`/`LarkResign`） |
| `AndroidAppHelper` 直连 | — | 均经 `Class.forName("android.app.AndroidAppHelper")` 字符串反射（非 XposedHelpers 直引），无 legacy 类型依赖 |

## 4. legacy 元数据与入口（阶段 1 删除清单的基线）

- `assets/xposed_init`（4 个入口类，内容见 baseline.md）
- Manifest meta-data ×4：`xposedmodule`、`xposeddescription`、`xposedminversion=82`、`xposedscope`（→ `@array/xposedscope`，位于 `app/src/main/res/values/arrays.xml`）
- legacy 入口实现：`AntiRecall`、`ResignTracker`、`ProfileCapture` 实现 `IXposedHookLoadPackage`（+前两者 `IXposedHookZygoteInit`）；`FuckLarkSettingsHook` 为纯委托包装（实现 `IXposedHookLoadPackage`，转发到 `FuckLarkSettings`）
- stubs 目录 7 个文件：`de.robv.android.xposed` 下 6 个（XposedBridge/XposedHelpers/XC_MethodHook/IXposedHookLoadPackage/IXposedHookZygoteInit/callbacks.XC_LoadPackage）+ `java.lang.invoke.LambdaMetafactory`（d8 no-desugaring 编译期桩，与 Xposed 无关，删除 stubs 时需甄别）
