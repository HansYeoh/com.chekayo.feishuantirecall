# 阶段 0 记录 A：构建基线

- **日期**：2026-10-05
- **迁移分支**：`libxposed-api-102-migration`（基于 `main` = `544b593` 创建，阶段开始时 HEAD = `e61b868`，仅含迁移计划文档一个提交）
- **执行环境**：Windows 10 x64 / Git Bash + PowerShell 5.1

## 1. 构建执行

- 命令：`./build.ps1`（无参数，全工具链自动探测）
- 结果：**成功**，8 个阶段（NDK 编译 → javac stubs → javac module → d8 → aapt2 → 塞包 → zipalign → apksigner）全部通过。
- 证明材料：
  - 完整控制台日志：`baseline-build.log`（本记录同目录，已改名为 .log.txt 以便入库）
  - 第一次构建因日志重定向目标写在了 `build/` 内，被脚本开头的 `Remove-Item build/` 清掉；随后在构建目录外重跑一次，日志完整保留。两次构建均 exit 0，产物一致（同证书、同内容）。

### 工具链（自动探测结果）

| 工具 | 路径/版本 |
|---|---|
| JDK | Android Studio jbr，javac 21.0.10 |
| Android SDK | `C:\Users\Weirdo\AppData\Local\Android\Sdk` |
| Build-Tools | 37.0.0 |
| android.jar | platforms/android-37.0 |
| NDK | 29.0.14206865（完整 native 重编译，非复用 so 模式） |
| Keystore | 仓库根 `debug.keystore`（本地构建证书，已 gitignore） |

日志中的 javac「源值 8 已过时」警告为既有现象，与本阶段无关。

## 2. 基线 APK 信息

| 项目 | 值 |
|---|---|
| 文件 | `feishu-antirecall.apk`（仓库根，1492.8 KB） |
| versionCode | 31 |
| versionName | 1.8.9（Manifest 与 version.json 一致；MODULE_VERSION/MODULE_VERSION_CODE 同步） |
| 包名 | com.chekayo.feishuantirecall |
| minSdk / targetSdk | 22 / 34（aapt2 badging 实测） |
| APK SHA-256 | `f331203f3a4646770e8d974c4d945a0bc08c7669a672820c9871b0eda8df6a5f` |
| 签名方案 | V3.0 |
| 证书 SHA-256 | `7c20f829bb61d2758f203130b6e3f5ef0d00141def1bd383c11efeeee23ac81d`（= 源码 `AntiRecall.EXPECTED_SIG`，签名自校验通过的前提） |

### APK 内容清单（classes.dex / lib / assets）

| 条目 | 大小 | 说明 |
|---|---|---|
| `classes.dex` | 222,956 B | 单 dex（d8 --min-api 22 --no-desugaring） |
| `lib/arm64-v8a/libantirecall.so` | 53,416 B | 防撤回 inline hook（sqlite3_step） |
| `lib/arm64-v8a/libresign.so` | 27,496 B | 离职统计/花名册采集 |
| `assets/reward.png` | 322,919 B | |
| `assets/xposed_init` | 175 B | **存在 —— legacy 入口元数据，迁移后必须移除**（P0 验收项） |

`assets/xposed_init` 内容（4 个 legacy 入口类）：

```
com.chekayo.feishuantirecall.AntiRecall
com.chekayo.larkresign.ResignTracker
com.chekayo.feishuantirecall.FuckLarkSettingsHook
com.chekayo.feishuantirecall.ProfileCapture
```

### 既有 APK 保护

按文档要求"不覆盖当前已验证的 APK"：构建前已把根目录旧产物备份到
`tools/feishu-antirecall-baseline-v1.8.9-pre-migration.apk`（SHA-256
`3a9256ce426fdaf4bc42b0ba3ca0c92c08df6b5ea2fdc9f2ec0c5c8b0563b2fe`，gitignore 覆盖，不入库）。
注意：build.ps1 第 366 行会把签名产物直接写到仓库根 `feishu-antirecall.apk`，本阶段实际发生了一次覆盖（内容即基线构建本身，与备份仅 zip 时间戳差异）。

## 3. 与迁移相关的基线事实

- 桌面/普通进程（DataViews、LauncherActivity、SettingsPanel）不 import 任何 legacy Xposed 类型 —— 模块自身 UI 不依赖 Xposed 运行时，迁移不涉及。
- Manifest 现存 legacy 元数据 4 项：`xposedmodule` / `xposeddescription` / `xposedminversion=82` / `xposedscope`（resource 指向 `@array/xposedscope`）。
- `AntiRecall` 与 `ResignTracker` 额外实现 `IXposedHookZygoteInit`（initZygote 记录 `MODULE_PATH`）—— API 102 无 zygote 阶段回调，需按阶段 3 文档改由 `getModuleApplicationInfo().sourceDir` 等价初始化。
- DataViews 通过反射读 `AntiRecall.MODULE_PATH` 静态字段（`DataViews.moduleApkPath()`），迁移后该字段语义必须保留。
