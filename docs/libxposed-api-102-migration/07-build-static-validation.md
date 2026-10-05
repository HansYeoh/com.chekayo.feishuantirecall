# 阶段 6：本机构建与静态验证

## 目标

在真机测试前确认：构建链、APK 结构、依赖边界、legacy 清理和 native/config/data 不变。

## 1. Windows 构建

```powershell
./build.ps1
```

必须验证：

- javac stubs 成功。
- javac module 成功。
- d8 成功。
- aapt2 compile/link 成功。
- zipalign 成功。
- apksigner 成功。
- APK 安装包生成。
- vendored AAR 指纹校验通过（阶段 1 审计建议，防止二进制被无意替换）：

```powershell
Get-FileHash third_party/libxposed/api-102.0.0.aar -Algorithm SHA256
# 期望: 423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0
```

## 2. Linux 构建链检查

Linux 环境执行：

```bash
./build.sh
```

如果当前没有 Linux 构建环境，至少对 `build.sh` 做静态对照检查，确保与 PowerShell 版本使用相同：

- API 102 AAR。
- classes.jar classpath。
- min API 29。
- 默认 D8 desugaring。
- modern metadata 根目录打包。

## 3. Legacy 静态扫描

```powershell
rg -n --hidden --glob '!*.apk' --glob '!*.idsig' `
  'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook|IXposedHook|xposed_init' `
  app stubs build.ps1 build.sh scripts
```

预期：

- 源码、stubs、构建脚本中无 legacy API 引用。
- 文档中可以提到 legacy，但不要把文档匹配误认为源码残留。

## 4. Native/config/data diff 检查

```powershell
git diff -- native/jni
git diff -- app/src/main/assets
```

检查配置和数据相关文件是否只有必要的框架调用层变更。

## 5. APK 结构检查

查看 ZIP 条目：

```powershell
jar tf feishu-antirecall.apk | Select-String 'META-INF/xposed|assets/xposed_init|lib/arm64-v8a|classes.dex'
```

必须存在：

```text
classes.dex
lib/arm64-v8a/libantirecall.so
lib/arm64-v8a/libresign.so
META-INF/xposed/java_init.list
META-INF/xposed/module.prop
META-INF/xposed/scope.list
```

必须不存在：

```text
assets/xposed_init
```

## 6. 元数据内容检查

```powershell
jar xf feishu-antirecall.apk META-INF/xposed/java_init.list META-INF/xposed/module.prop META-INF/xposed/scope.list
Get-Content META-INF/xposed/java_init.list
Get-Content META-INF/xposed/module.prop
Get-Content META-INF/xposed/scope.list
```

确认：

- 入口类只有一个。
- `minApiVersion=102`。
- `targetApiVersion=102`。
- `staticScope=true`。
- `autoHotReload=true`。
- scope 只有国内版和国际版包名。

## 7. API 类不应重复打包

检查 DEX 中不应包含模块自己的 API 实现副本：

```powershell
# 使用本机已有的 dexdump / baksmali / jadx 工具检查 classes.dex
# 目标：不存在 io/github/libxposed/api/ 的模块打包实现类
```

API 102 `classes.jar` 只作为编译 classpath。

## 8. 构建回归失败时的定位顺序

### 找不到 `XposedModule`

检查：

- AAR 是否成功解包。
- classpath 是否指向 `classes.jar`。
- Windows 使用分号，Linux 使用冒号。

### lambda 编译失败

检查：

- 是否保留 `stubs/java/lang/invoke/LambdaMetafactory.java`。
- stubs 是否先于 module 编译。
- D8 是否仍然带有 `--no-desugaring`。

### 框架发现不到入口

检查：

- 文件是否位于 APK 根目录 `META-INF/xposed/`。
- 是否误放到了 `assets/META-INF/xposed/`。
- `java_init.list` 的类名是否完整。
- 文件是否多了 BOM 或错误的包名。

### 运行时报 `MODULE_PATH` 为空

检查：

- `onModuleLoaded()` 是否执行。
- 是否调用 `getModuleApplicationInfo().sourceDir`。
- 是否在业务 hook 安装前完成赋值。

## 阶段出口

- Windows 构建通过。
- Linux 构建脚本与 Windows 逻辑一致。
- APK 结构通过。
- legacy 扫描通过。
- native/config/data 边界通过。
- 未修改版本号。
