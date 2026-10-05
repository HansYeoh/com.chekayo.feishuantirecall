# 阶段 1 执行记录：构建链与 modern 元数据

- 日期：2026-10-05
- 分支：`libxposed-api-102-migration`
- 基线：阶段 0（commit `d132dcc`）
- 执行文档：[02-build-and-metadata.md](../../02-build-and-metadata.md)

## 变更清单

### 1. Vendored API 依赖

- 新增 `third_party/libxposed/api-102.0.0.aar`（`io.github.libxposed:api:102.0.0`，Maven Central 原件）。
- 实测 SHA-256 = `423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0`，与 02 文档记录一致。
- 新增 `third_party/libxposed/README.md` 记录依赖摘要（artifact/type/sha256/source + 替换规则）。

### 2. 构建脚本（build.ps1 / build.sh 同步修改）

- AAR 存在性检查，缺失即 fail-fast；从 AAR 解出 `build/libxposed/classes.jar`（ps1 用 .NET `ZipFile`，sh 用 JDK 自带 `jar` 工具，不新增 unzip 依赖）。
- javac module classpath 加入 `classes.jar`（ps1 分隔符 `;`，sh 为 `:`）。
- d8 输入仍只含模块自身类文件目录（`build/app`），`classes.jar` 不进 DEX。
- d8 删除 `--no-desugaring`，`--min-api` 22 → 29。
- aapt2 link `--min-sdk-version` 22 → 29（target 34 不变）。
- 第 5 步打包将 `META-INF/xposed/{java_init.list,module.prop,scope.list}` 追加到 APK 根目录（不再依赖 assets 路径）。
- stubs 编译步骤加注释：`LambdaMetafactory.java` 是 `-source 8` 的 lambda 编译桩，与 Xposed 无关，清理 legacy stubs 时必须保留。

### 3. modern 元数据（`app/src/main/resources/META-INF/xposed/`）

- `java_init.list`：`com.chekayo.feishuantirecall.FeishuKitModule`（入口类在阶段 3 创建，本阶段先注册元数据）。
- `scope.list`：`com.ss.android.lark` / `com.larksuite.suite`（取自原 `arrays.xml` 的 `xposedscope`）。
- `module.prop`：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`、`exceptionMode=protective`、`autoHotReload=true`，另补身份键 `id/name/version/versionCode/author/description`（理由见偏差 2）。

### 4. AndroidManifest 清理

- 删除 legacy meta-data：`xposedmodule` / `xposeddescription` / `xposedminversion`(82) / `xposedscope`。
- `minSdkVersion` 22 → 29；`targetSdkVersion` 34 不变；`versionName/Code` 1.8.9/31 不变。
- 描述转为 `android:description="@string/module_description"`，对应 string 资源已加入 `values/strings.xml`。

### 5. legacy 元数据文件删除

- 删除 `app/src/main/assets/xposed_init`（4 入口：AntiRecall / ResignTracker / FuckLarkSettingsHook / ProfileCapture）。
- 删除 `app/src/main/res/values/arrays.xml`（已 grep 验证：Java 代码与资源无 `R.array` / `@array/xposedscope` 引用，仅一处注释提及）。

## 偏差与理由

1. **02 文档第 2 节第 9 条「不再编译 stubs/de/robv/」暂缓执行。**
   现役 9 个业务文件、40 处 hook 仍引用 `XC_MethodHook`/`XposedHelpers` 等 legacy 类型，`stubs/de/robv` 是它们唯一的编译来源，阶段 1 移除会直接破坏构建。README 推荐顺序第 6 步明确「等所有 legacy 引用清零后，再删除 legacy stubs 和入口文件」。且 stubs 从未进入 DEX（d8 输入仅 `build/app`，本次构建输出已验证），暂缓不影响本阶段出口。将在阶段 4 hook 迁移收口时一并执行。
2. **module.prop 补充身份键。**
   02 文档「第一版建议」只列 5 个 modern 键；框架按 `minApiVersion/targetApiVersion` 判定模块可用性，按 `id/name/author/description/version(Code)` 在管理界面展示与标识模块，只写 5 键会导致识别/展示异常。身份键取值全部来自现有 Manifest（`1.8.9`/`31`，冻结值不变；author 取上游 haikow）。
3. **`assets/xposed_init` 在阶段 1 即删除。**
   02 文档第 5 节允许「先保留以便构建链对照」，但同文档阶段出口明确要求 APK 不含 `assets/xposed_init`，README 全局不变量同。按出口门槛提前删除。中间产物 APK 在任何框架下均不可加载，属预期（真机验证在阶段 6/7 之后）。

## 验证结果（Windows 本机构建）

- `./build.ps1` 8 阶段全过（NDK 29 → javac 21 → d8 → aapt2 → zipalign → apksigner），完整日志见 [build.log.txt](build.log.txt)（中文 GBK 乱码为已知无害现象）。
- 产物 `feishu-antirecall.apk`：1493KB，SHA-256 = `1f56db92722e07a7747ace5d2334305228f4cdaf4dc290eb44f57cec19275ca9`（ZIP 时间戳导致每次构建不同，正常）；签名证书与阶段 0 基线一致（本地 debug 证书，见 records/phase-0/baseline.md，不入上游）。
- 阶段出口检查（jar tf）：
  - 存在：`META-INF/xposed/java_init.list`、`META-INF/xposed/module.prop`、`META-INF/xposed/scope.list` ✅
  - 不存在：`assets/xposed_init` ✅
- 元数据回读：三个文件内容与源文件一致，UTF-8 中文无损坏，均带末尾换行 ✅
- DEX 纯度：对 `classes.dex` 二进制 grep `io/github/libxposed` 0 命中 → API 实现类未进模块 DEX ✅
- Manifest 编译产物（aapt2 dump xmltree/badging）：`minSdkVersion=29`、`targetSdkVersion=34`、`android:description=@0x7f020000`（module_description 资源）、`versionCode=31`/`versionName=1.8.9` ✅
- 冻结边界：`git diff -- native/jni` 为空（零改动）✅；`app/src/main/assets` 仅本阶段计划内的 `xposed_init` 删除 ✅
- `build.sh` 未在本机执行（Windows 环境），已与 build.ps1 逐条对照同步（AAR 检查/解包、classpath、min-api 29、去 --no-desugaring、min-sdk 29、META-INF 打包），实际 Linux 运行验证按计划在阶段 6 进行。

## 遗留与下一步

- 入口类 `FeishuKitModule` 尚不存在（阶段 3），当前 APK 在任何框架下均不可加载，属预期中间态。
- 下一步：阶段 2（运行时桥接层）—— `ModuleRuntime`/`ModuleLog`/反射/hook 桥接，先让新 API 单独可编译。
