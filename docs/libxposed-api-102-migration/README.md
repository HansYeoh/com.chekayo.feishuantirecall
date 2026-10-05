# libxposed API 102 迁移执行文档

- **项目**：FeishuKit / `com.chekayo.feishuantirecall`
- **目标**：从 legacy Xposed API 迁移到 libxposed API 102
- **文档日期**：2026-10-05
- **当前状态**：阶段 0、1、2、3 已完成且审计通过（阶段 3 见 `records/phase-3/`，非阻塞备注已修复 `d9de0e3`）；阶段 4 已实现待审计（见 `records/phase-4/`：40 注册点/122 日志/19 反射全部切换，stubs/de/robv 已删，DEX legacy 计数清零）
- **适用分支**：从当前 `main` 分支创建迁移分支

## 1. 目标与边界

本次迁移只改变模块与 Xposed 框架交互的方式，不改变现有业务行为。

### 1.1 目标

- 删除 `de.robv.android.xposed` legacy API 依赖。
- 删除 `assets/xposed_init` 和 legacy 入口元数据。
- 使用一个 `XposedModule` 入口类。
- 使用 libxposed API 102 的 interceptor-chain hook 模型。
- 保持防撤回、防已读、去水印、审计屏蔽、下载解锁、配置桥、数据格式和 native inline hook 的行为不变。
- 在本机完成构建、APK 结构检查和静态检查后，再进行真机回归。
- 真机验证通过后，再向上游 `haikow/com.chekayo.feishuantirecall` 提交 PR。

### 1.2 明确不做

- 不保留 legacy 与 modern 双模入口。
- 不保留 `de.robv`、`XposedBridge`、`XposedHelpers`、`XC_MethodHook` 等 legacy 类型。
- 不改 `native/jni/` 源码。
- 不改配置文件格式、数据文件格式和 native JNI 方法名。
- 本次不修改 `version.json`、Manifest 版本号和 `AntiRecall` 内置版本号。
- 不把 hot reload 的安全清理问题伪装成已经完整支持。

## 2. 已确认的产品决策

| 项目 | 决策 | 执行要求 |
|---|---|---|
| 框架模式 | 单模 modern API | 只保留 API 102 入口 |
| legacy 用户 | 不再兼容 | 删除 legacy 元数据；老框架不再显示或加载属于预期代价 |
| Android 下限 | Android 10 / API 29 | Manifest、aapt、D8 使用 29 作为有效下限 |
| Xposed API | 102/102 | `minApiVersion=102`，`targetApiVersion=102` |
| hot reload | 声明能力但安全拒绝优先 | `autoHotReload=true`；当前存在 native/线程时由 `onHotReloading()` 拒绝，不能宣称完整 reload |
| 版本号 | 暂不变更 | 由上游维护者决定 release bump |
| 上游目标 | 提交 PR | 本机验证完毕后再提交 |
| native | 零改动 | `native/jni/` diff 必须为空 |

> `minApiVersion/targetApiVersion` 是 Xposed API 版本，`android:minSdkVersion` 是 Android 系统版本，不能混为一谈。

## 3. 关键修正

原始迁移草案需要补充或修正以下内容：

1. API 102 的 Java 入口元数据除了 `module.prop`、`scope.list`，还必须有 `java_init.list`。
2. `io.github.libxposed:api:102.0.0` 的发布物是 AAR，构建时使用其中的 `classes.jar`，而不是假定存在独立普通 JAR。
3. `onPackageLoaded` 和 `onPackageReady` 不能都执行完整业务分发，否则会重复 hook。建议以 `onPackageReady` 作为唯一完整分发入口。
4. API 102 hot reload 要求停止线程、注销外部回调并卸载 native hook。当前 native inline hook 没有完整 teardown，因此第一版必须 fail-closed。
5. “业务逻辑不变”应按行为等价验收，而不是要求所有 Java hook 文件零行变更。native 源码、配置语义和数据格式可以做到零改动。
6. 当前仓库的 `scripts/pr_gate.sh` 要求 PR 中版本号递增；本次不改版本号时，该 gate 需要由维护者手工处理或另行调整策略。

## 4. 阶段总览

| 阶段 | 文档 | 主要产物 | 阶段出口 |
|---|---|---|---|
| 0 | [基线与清单](01-baseline-and-inventory.md) | 构建基线、hook 清单、行为基线 | 清单冻结，native/config/data 边界确认 |
| 1 | [构建与元数据](02-build-and-metadata.md) | API 102 依赖、modern 元数据、构建链 | APK 能生成且根目录元数据正确 |
| 2 | [运行时桥接层](03-runtime-bridge.md) | 日志、反射、hook、模块路径桥接 | 桥接层独立可编译、无 legacy 类型 |
| 3 | [入口与生命周期](04-entry-lifecycle.md) | 单一 `XposedModule` 入口 | 生命周期只分发一次，ClassLoader 正确 |
| 4 | [逐点迁移 hook](05-hook-migration.md) | 全部 hook 转为 interceptor | 业务判断和 hook 覆盖保持等价 |
| 5 | [hot reload 安全策略](06-hot-reload.md) | reload gate、handle 管理 | 不安全场景拒绝 reload，不崩溃不重复安装 |
| 6 | [本机验证](07-build-static-validation.md) | APK、静态检查报告、构建日志 | 两套脚本和 APK 结构验证通过 |
| 7 | [真机回归](08-device-regression.md) | 功能矩阵、日志、问题记录 | 核心功能和多进程行为通过 |
| 8 | [上游 PR](09-upstream-pr.md) | commit、PR 描述、测试证据 | 可提交上游审阅 |

## 5. 推荐执行顺序

1. 先做阶段 0，冻结基线，不修改业务代码。
2. 完成阶段 1 的依赖、元数据和构建链。
3. 完成阶段 2 的桥接层，先让新 API 能单独编译。
4. 完成阶段 3 的单入口和生命周期分发。
5. 按阶段 5 文档的顺序迁移 hook，不要一次性全局替换。
6. 等所有 legacy 引用清零后，再删除 legacy stubs 和入口文件。
7. 阶段 6 本机验证通过后，才进入真机安装。
8. 真机回归通过后，整理 commit 并提交上游 PR。

## 6. 全局不变量

以下条件在整个迁移完成后必须成立：

- `native/jni/` 无 diff。
- 配置 JSON、数据 JSON/JSONL、数据库路径语义不变。
- `MODULE_PATH` 仍然能被 native loader 和 `DataViews` 使用。
- 主进程与后台进程的 hook 范围不扩大、不缩小。
- 相同进程中同一 hook 不重复注册。
- 所有原有短路分支仍然短路。
- 所有原有参数修改在 `proceed(newArgs)` 前生效。
- 所有原有 after-hook 的返回值修改仍然生效。
- APK 不包含 `assets/xposed_init`。
- APK 根目录包含 `META-INF/xposed/java_init.list`、`module.prop`、`scope.list`。
- 源码中不存在 legacy Xposed 类型引用。
- `version.json` 和现有版本常量保持不变。

## 7. 风险优先级

### P0

- 元数据打包路径错误，导致框架发现不到入口。
- AAR 中的 `classes.jar` 未加入 javac classpath。
- `onPackageLoaded`/`onPackageReady` 双重分发导致重复 hook。
- interceptor 参数列表不可变，但代码仍直接修改 `args`。
- hot reload 允许旧 native hook 和旧线程继续存活。

### P1

- `MODULE_PATH` 没有从 `getModuleApplicationInfo().sourceDir` 初始化。
- 动态 ClassLoader 下反射到了错误的类。
- `hookAllMethods` / `hookAllConstructors` 语义实现不完整。
- after-hook 只调用 `proceed()`，却丢失原有 `setResult()` 逻辑。
- 版本不变导致 `pr_gate.sh` 的 G7 失败。

### P2

- 日志优先级或 tag 改变，影响旧日志对照。
- `values/arrays.xml` 删除后仍有残余资源引用。
- README 没有同步说明 API 102 和 Android 10 要求。

## 8. 完成定义

只有同时满足以下条件，迁移才算完成：

1. 本机 Windows 构建通过。
2. Linux 构建脚本逻辑同步且可执行。
3. `rg` 静态扫描无 legacy API 残留。
4. APK 元数据位于正确的根目录路径。
5. APK 不重复包含 libxposed API 实现类。
6. native/config/data 保护边界通过 diff 检查。
7. LSPosed modern 环境能够加载模块并进入目标进程。
8. 国内版、国际版和后台进程回归通过。
9. hot reload 不安全时能稳定拒绝，不发生崩溃或重复 hook。
10. PR 描述包含测试矩阵、版本号暂不变更的说明和已知限制。
