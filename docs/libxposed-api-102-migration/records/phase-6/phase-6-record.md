# 阶段 6 执行记录：本机构建与静态验证

- **执行日期**：2026-10-06
- **对应文档**：[07-build-static-validation.md](../../07-build-static-validation.md)
- **分支**：`libxposed-api-102-migration`
- **前置状态**：阶段 5 已完成并通过复审（`7c34d68` 实施 / `efd3efd` 审计 P1 修复 / `b309481` 复审入档），工作区干净，HEAD = `b309481`
- **diff 基线**：`main` = `544b593`（= merge-base，迁移全程的对照点）

## 1. 验证矩阵与总结果（对照 07 文档各节）

| 07 文档 | 验证项 | 结果 | 证据 |
|---|---|---|---|
| §1 | Windows `build.ps1` 全链路（8 阶段）+ AAR 指纹 | **PASS** | `build.log.txt` / `aar-hash.log` |
| §2 | Linux 构建链检查（无实机环境 → 静态对照 + `bash -n`） | **PASS**（按文档许可路径） | `build-scripts-diff-vs-main.diff` + 本记录 §3 |
| §3 | legacy 静态扫描（`app stubs build.ps1 build.sh scripts`） | **PASS**（零命中） | `legacy-scan.log` |
| §4 | native/config/data diff 检查 | **PASS** | `frozen-boundary.log` |
| §5 | APK 结构检查 | **PASS** | `apk-structure.log` |
| §6 | 元数据内容检查 | **PASS** | `apk-structure.log` |
| §7 | API 类不重复打包（dexdump 类定义扫描） | **PASS** | `dex-evidence.log` |
| — | 附加基线对照（badging / 证书 / 版本常量） | **PASS** | `apk-structure.log` / `frozen-boundary.log` |

## 2. §1 Windows 构建

环境（自动探测，日志头可复核）：JDK 21.0.10（Android Studio jbr）/ SDK build-tools **37.0.0** / NDK **29.0.14206865** / android.jar = platforms/android-37.0 / keystore = 仓库根 `debug.keystore`。

`build.ps1` **EXIT=0**，8 个阶段全部成功（与 07 §1 逐项对应）：

| 07 §1 要求 | 对应构建阶段 | 结果 |
|---|---|---|
| javac stubs 成功 | `== 1. javac stubs ==` | PASS（仅 `-source/-target 8` 过时警告，预期内） |
| javac module 成功 | `== 2. javac module ==` | PASS（classpath = `build\stubs;build\libxposed\classes.jar`） |
| d8 成功 | `== 3. d8 -> classes.dex ==` | PASS（`--min-api 29`，默认 desugaring） |
| aapt2 compile/link 成功 | `== 4. ==` | PASS（`--min-sdk-version 29`） |
| zipalign 成功 | `== 6. ==` | PASS |
| apksigner 成功 | `== 7. apksigner sign ==` + DONE 段 `verify --print-certs` | PASS |
| APK 安装包生成 | 根目录 `feishu-antirecall.apk`，**1501KB** | PASS（与阶段 4/5 出口同量级） |
| （额外）NDK native 重编 | `== 0. NDK compile ==` | PASS（libantirecall.so 52.2KB / libresign.so 26.9KB） |

**vendored AAR 指纹校验**（阶段 1 审计要求的硬性项，07 §1 明确期望值）：

```
实测:   423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0
期望:   423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0   → 逐字符一致
```

签名证书 SHA-256 = `7c20f829bb61d2758f203130b6e3f5ef0d00141def1bd383c11efeeee23ac81d`，与阶段 0 基线及 `EXPECTED_SIG` 一致（本地调试证书，属既有决策；上游 PR 采用贡献者自身签名属阶段 8 事项）。

> 归档注记：`build.log.txt` 中 PowerShell 输出的中文标题（如阶段 5 名）为混合编码显示瑕疵，其余内容（阶段标记、警告、DONE、证书指纹）均为 ASCII，不影响审计核对；与阶段 2/5 归档日志口径一致。

## 3. §2 Linux 构建链检查（静态对照）

本机无 Linux Android 构建环境：有 WSL(Ubuntu)，但 SDK/NDK 均为 Windows 宿主二进制（`aapt2.exe`、`clang++.exe` 无法在 Linux 侧执行），在 WSL 内安装全套 Linux SDK/NDK 超出本阶段范围。07 §2 明确许可：「如果当前没有 Linux 构建环境，至少对 `build.sh` 做静态对照检查」。执行了三层证据：

1. **迁移引入的脚本 diff 逐行对称**（`build-scripts-diff-vs-main.diff`，154 行）：ps1 与 sh 得到**同一组** 5 处改动——
   - AAR fail-fast 检查 + 解出 `classes.jar`（仅编译期）；
   - javac module classpath 追加 `classes.jar`（分隔符各随平台 `;` / `:`）；
   - d8 `--min-api 22 --no-desugaring` → `--min-api 29`（默认 desugaring）；
   - aapt2 link `--min-sdk-version 22` → `29`；
   - 阶段 5 打包追加 `META-INF/xposed/` 三件套（缺失即 fail-fast）。
2. **07 §2 要求的 5 个对照点逐项核对**（基于当前工作区脚本内容）：

   | 对照点 | build.sh | build.ps1 | 一致 |
   |---|---|---|---|
   | API 102 AAR | `third_party/libxposed/api-102.0.0.aar`，fail-fast（:88-92） | 同（:278-287） | ✓ |
   | classes.jar classpath | `-cp "$BUILD/stubs:$BUILD/libxposed/classes.jar"`（:120） | `-cp "$build\stubs;$build\libxposed\classes.jar"`（:334） | ✓（仅分隔符平台差异） |
   | min API 29 | d8 `--min-api 29`（:126）+ aapt2 `--min-sdk-version 29`（:133） | d8（:347/349）+ aapt2（:359） | ✓ |
   | 默认 D8 desugaring | 无 `--no-desugaring`（:126） | 无（:347/349） | ✓ |
   | modern metadata 根目录打包 | stage 目录 → `zip` 进 APK 根 `META-INF/xposed/`（:137-147），缺文件 die | 直接以 zip entry 写入 APK 根（:376-382），缺文件 throw | ✓ |

   其余关键参数亦逐一对齐：NDK 编译 flags（`--target=aarch64-linux-android24 -fPIC -shared -O2 -fvisibility=hidden -fno-rtti -fno-exceptions -mno-outline-atomics -static-libstdc++` + 同一组链接开关）、stubs `--release 8`、javac `-source/-target 8 -bootclasspath`、`zipalign -p -f 4`、apksigner 参数与输出路径。
3. **`bash -n build.sh` 语法检查通过**（脚本可执行性的本机可达证据）。

**基线既有差异（非迁移引入，无需处理）**：`build.ps1` 在 NDK 缺失时回退复用发布版 APK 的 .so，`build.sh` 缺 NDK 直接 die——该差异在基线 `544b593` 已存在（`git show 544b593:build.sh` 第 63 行即 `die`），本次迁移未触碰该策略。实机 Linux 构建留待上游 CI / 维护者环境复跑（阶段 8 PR 材料可注明）。

## 4. §3 Legacy 静态扫描

按 07 §3 原命令（含 `--hidden`，范围 `app stubs build.ps1 build.sh scripts`）：

```
rg -n --hidden --glob '!*.apk' --glob '!*.idsig' \
  'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook|IXposedHook|xposed_init' \
  app stubs build.ps1 build.sh scripts
→ exit=1（零命中）
```

- 源码、stubs（阶段 4 后仅剩 `java/lang/invoke/LambdaMetafactory.java` 编译桩，与 Xposed 无关）、构建脚本、scripts 目录：**无任何 legacy API 引用**。
- 信息性全仓扫描（排除 `docs/` 与 `.git/`）：仅命中 3 个文档类文件（根 `README.md`、两篇复盘笔记），属 07 §3 明文允许的「文档中可以提到 legacy」，非源码残留。阶段 2 遗留的「桥接层 javadoc legacy 字样」已在阶段 4 提前关闭（16 处注释改写为不含 token 的等价表述），本次扫描复核确认。

## 5. §4 Native / config / data diff 检查

对比基线 `544b593`（全迁移周期累计），详见 `frozen-boundary.log`：

| 检查项 | 结果 |
|---|---|
| `git diff 544b593..HEAD -- native/jni` | **空**（零改动，含 And64InlineHook/antirecall/resign 全部文件） |
| `git diff 544b593..HEAD -- app/src/main/assets` | **仅** `assets/xposed_init` 删除（4 行）——即迁移本意的 legacy 入口元数据移除；业务资产（reward.png）不动 |
| `version.json` blob | `b7d67828…` 前后一致（零改动） |
| `Config.java` | blob `165f18ed…` 一致（阶段 0 冻结值） |
| `ConfigProvider.java` | blob `e7683809…` 一致 |
| `AccountPaths.java` | blob `90de95f8…` 一致 |
| `ArchiveSync.java` | blob `8fe30933…` 一致 |
| `ProfileBulk.java` | blob `9ed4fa18…` 一致 |
| `NotifArchive.java` | blob `cb4b0a3b → 4d283347`：**唯一**有变动的 config/data 邻接文件，diff 为 1 行注释术语更新（`handleLoadPackage 早期执行` → `install 分发早期执行`，阶段 3 生命周期命名对齐），代码零变化，符合 07 §4「只有必要的框架调用层变更」口径 |
| AntiRecall 内置版本常量 | `MODULE_VERSION="1.8.9"` / `MODULE_VERSION_CODE=31` / `EXPECTED_SIG` 前后逐字一致 |

## 6. §5 / §6 APK 结构与元数据内容

APK 条目全清单（14 个 entry）见 `apk-structure.log`。逐项结论：

**§5 必须存在（全部在位）**：`classes.dex`、`lib/arm64-v8a/libantirecall.so`、`lib/arm64-v8a/libresign.so`、`META-INF/xposed/java_init.list`、`META-INF/xposed/module.prop`、`META-INF/xposed/scope.list`。
**§5 必须不存在**：`assets/xposed_init`（assets 下仅 `reward.png`）——不存在，PASS。

**§6 元数据内容**（`cat -A` 字节级核对，`$`=LF，无 CR、无 BOM）：

| 检查项 | 实测 | 结论 |
|---|---|---|
| 入口类只有一个 | `java_init.list` 单行 `com.chekayo.feishuantirecall.FeishuKitModule` | PASS |
| `minApiVersion=102` | module.prop 第 8 行 | PASS |
| `targetApiVersion=102` | module.prop 第 9 行 | PASS |
| `staticScope=true` | 在位 | PASS |
| `autoHotReload=true` | 在位（配合阶段 5 的门控安全策略） | PASS |
| scope 只有国内版和国际版 | `scope.list` 恰两行：`com.ss.android.lark`、`com.larksuite.suite` | PASS |
| 身份键未动 | `id=com.chekayo.feishuantirecall` / `version=1.8.9` / `versionCode=31` / `author=haikow` | PASS |

badging 与二进制 manifest 双确认：`versionCode='31' versionName='1.8.9'`、`minSdkVersion:'29'`（badging + uses-sdk AXML 属性）、`targetSdkVersion:'34'`、`native-code: 'arm64-v8a'`——版本号未修改（阶段出口硬性项），Android 下限 29 / 目标 34 与文档一致。

## 7. §7 API 类不应重复打包

采用 07 §7 建议的 dexdump 路径（build-tools/37.0.0），对本次构建的 `classes.dex`：

1. **类定义扫描（权威判据）**：DEX 类定义共 173 个，其中 `io/github/libxposed` 类定义数 = **0**。API 实现类未被模块打包，`classes.jar` 严格停留在编译期 classpath——PASS。
2. **类定义包名分布**：169 × `com/chekayo/feishuantirecall` + 4 × `com/chekayo/larkresign`，无第三方/API/stub 实现类混入（LambdaMetafactory 桩同样只进 javac、不进 DEX）。
3. **API 类型引用明细**（合法消费面）：`XposedInterface`、`XposedInterface$Chain`(72)/`$HookHandle`(7)/`$Hooker`(53)、`XposedModule`、以及 `ModuleLoadedParam`/`PackageLoadedParam`/`PackageReadyParam`/`HotReloadingParam`/`HotReloadedParam` 各 2 处——与桥接层 + 单入口 + 门控架构一一对应。
4. **原始 dex 字符串计数（与阶段 4/5 对账口径，`grep -aob | wc -l`）**：legacy 七 token（de/robv、XposedBridge、XposedHelpers、XC_MethodHook、XC_LoadPackage、IXposedHook、xposed_init）**全 0**；`io/github/libxposed`=11 与阶段 4/5 持平；`GateSnapshot`=2、`inspectReloadSafety`=1 与阶段 5 复验一致。

### 对账说明：`HotReloadSafety` 5 → 7

本次实测 `HotReloadSafety`=**7**，阶段 5 记录写的是 5。核对结论：**7 是正确值，5 为陈旧计数**——

- 7 处 = 1 × `HotReloadSafety.java`（javac `-g` 的 SourceFile 字符串）+ 6 × 类型描述符（outer、`$1`、`$GateSnapshot`、`$Kind` 及含 `$Kind`/`$GateSnapshot` 的签名串）；
- 阶段 5 的「5」实测于 `7c34d68`（P1 修复**之前**）；`efd3efd` 新增 `GateSnapshot` 内部类与 `inspectReloadSafety()`，引入新的描述符/签名字符串，计数值应变为 7，但 `efd3efd` 复验只重测了 GateSnapshot=2 / inspectReloadSafety=1 / io=11 三项（与本次实测完全一致），`HotReloadSafety` 一项未复测；
- 阶段 5 归档日志中不含该计数的原始输出（当时为临时测量），无法从归档复现「5」；本次按字节上下文逐项分解（`dex-evidence.log` 注）佐证 7 = 修复后正确值。
- 属**记录口径陈旧**而非代码回归：同批次四项计数有三项与阶段 5 复验逐字一致，且源码自 `efd3efd` 起零改动（git 可证）。

## 8. §8 定位顺序

07 §8 的四类失败定位顺序（找不到 XposedModule / lambda 编译失败 / 框架发现不到入口 / MODULE_PATH 为空）为**预案**，本次构建一次通过，未触发任何定位路径。

## 9. 偏差与注记

1. **Linux 实机构建未执行**：无 Linux Android SDK/NDK 环境（WSL 在位但 Windows 宿主二进制不可跨用），按 07 §2 许可走静态对照 + `bash -n` + 逐行对称 diff 三层证据；实机 Linux 构建建议在上游阶段（CI/维护者环境）补跑。
2. **`build.log.txt` 中文标题编码瑕疵**：PowerShell 混合编码显示问题，主体内容 ASCII，不影响核对（与阶段 2/5 归档口径一致）。
3. **阶段 5 记录 `HotReloadSafety=5` 为陈旧计数**：本阶段实测并分解为 7（§7 对账），建议审计方知悉该记录值需按本记录口径更正（历史记录不改写，以本记录为准）。
4. `build.ps1` 覆盖根目录 `feishu-antirecall.apk` 为既有行为（迁移前基线备份在 `tools/feishu-antirecall-baseline-v1.8.9-pre-migration.apk`），非偏差。
5. 根目录 APK 整包 SHA-256 跨构建必变（ZIP 时间戳），产物对比一律以 badging + entry 清单 + 证书 digest 为准（阶段 0 既有方法论）。

## 10. 阶段出口对照（07 文档「阶段出口」逐条）

| 出口项 | 结果 |
|---|---|
| Windows 构建通过 | PASS（EXIT=0，8 阶段，AAR 指纹一致） |
| Linux 构建脚本与 Windows 逻辑一致 | PASS（静态对照 5 点逐项一致 + diff 对称 + `bash -n`） |
| APK 结构通过 | PASS（六必备条目在位、无 xposed_init） |
| legacy 扫描通过 | PASS（目标范围零命中） |
| native/config/data 边界通过 | PASS（native 零 diff；assets 仅 xposed_init 删除；config/data 5 blob 一致 + NotifArchive 注释行；version.json 一致） |
| 未修改版本号 | PASS（badging 31/1.8.9、module.prop、version.json、AntiRecall 常量四处一致） |

## 11. 下一步

进入**阶段 7 真机回归**（`08-device-regression.md`）——硬性验收：LSPosed modern 环境真实加载 `FeishuKitModule` 并进入目标进程；核心功能矩阵、多进程行为、hot reload 不安全场景真机拒绝（不崩溃、不重复 hook）。本阶段所有验证均为本机静态/构建层，真机可加载性仍待阶段 7 兑现。

## 12. 审计轮次

### 第一轮：通过，允许进入阶段 7（2026-10-06）

审计方复核 `3449c8d`（复审时工作区干净），确认本提交仅新增本机验证记录并更新状态，没有修改业务代码或构建逻辑。逐项确认通过：

- Windows `build.ps1` 全链路成功，8 个阶段均有归档证据；
- vendored AAR SHA-256 与期望值逐字符一致；
- APK 签名证书摘要与基线及 `EXPECTED_SIG` 一致；
- APK 必备条目齐全（classes.dex、两个 arm64 native 库、`META-INF/xposed/` 三件套），`assets/xposed_init` 不存在；
- modern 元数据无 BOM、使用 LF、入口单一、API 版本 102/102、`autoHotReload=true`、scope 两包名正确；
- Manifest/badging 均确认 minSdk 29 / targetSdk 34 / versionCode 31 / versionName 1.8.9；
- legacy 七类 token 在 DEX 中全部为 0；dexdump 类定义扫描确认 `io/github/libxposed` 定义数为 0，API 仅作为编译期依赖；
- native/jni 零 diff；version.json 零 diff；
- 配置/数据冻结文件均未发生业务代码变化，`NotifArchive.java` 仅一行注释术语更新；
- scripts、stubs、构建脚本目标范围 legacy 扫描零命中；
- `HotReloadSafety` 的阶段 5 计数差异（5 → 7）已合理解释为修复后常量池计数变化，不是代码回归。

**阶段限制确认（Linux 实机构建）**：审计确认 Linux 实机构建本阶段未执行的降级路径与 07 文档允许一致、原因与替代证据记录充分（WSL 中无可用的 Linux Android SDK/NDK；Windows SDK/NDK 二进制不能直接作为 Linux 构建链；已完成 `bash -n`、构建脚本迁移 diff 对称检查和关键参数逐项对照）。按审计方要求的准确表述：**Windows 构建通过，Linux 构建链静态检查通过；Linux 实机构建待上游 CI 或维护者环境补跑**。不阻塞阶段 6 出口。

**最终判断：阶段 6 通过。**
