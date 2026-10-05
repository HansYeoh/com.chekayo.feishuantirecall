# 阶段 1：构建链与 modern 元数据

## 目标

让手写构建链能够编译 API 102 入口，并将 modern Xposed 元数据放到 APK 根目录的正确路径。

## 1. Vendored API 依赖

API 102 发布物按 AAR 处理：

```text
third_party/libxposed/api-102.0.0.aar
```

构建时解出：

```text
build/libxposed/classes.jar
```

建议同时记录依赖摘要，避免后续替换二进制：

```text
artifact: io.github.libxposed:api:102.0.0
type: aar
sha256: 423484a6e1807e7a423c4b88fcd8176d104318259d91791877fed88fe91479d0
```

API 依赖只用于编译，不能进入最终模块 APK 的 DEX。

## 2. 构建脚本改动

同时修改：

```text
build.ps1
build.sh
```

要求：

1. 检查 vendored AAR 是否存在。
2. 从 AAR 解出 `classes.jar`。
3. 将 `classes.jar` 加入 javac module classpath。
4. 不把 `classes.jar` 目录作为 d8 输入。
5. D8 删除 `--no-desugaring`。
6. D8 `--min-api` 改为 29。
7. aapt2 `--min-sdk-version` 改为 29。
8. 保留 `stubs/java/lang/invoke/LambdaMetafactory.java`。
9. 不再编译 `stubs/de/robv/android/xposed/`。
10. 将 `META-INF/xposed` 文件追加到 APK 根目录，而不是 `assets/` 目录。

## 3. Modern 元数据文件

新增目录：

```text
app/src/main/resources/META-INF/xposed/
```

### `java_init.list`

```text
com.chekayo.feishuantirecall.FeishuKitModule
```

只列一个入口类，末尾保留换行。

### `scope.list`

```text
com.ss.android.lark
com.larksuite.suite
```

### `module.prop`

第一版建议：

```properties
minApiVersion=102
targetApiVersion=102
staticScope=true
exceptionMode=protective
autoHotReload=true
```

`autoHotReload=true` 只代表允许框架触发 reload 流程，实际是否放行由 `onHotReloading()` 决定。当前版本必须 fail-closed，详见 [阶段 5](06-hot-reload.md)。

## 4. AndroidManifest 清理

修改：

```text
app/src/main/AndroidManifest.xml
```

删除以下 legacy meta-data：

```xml
xposedmodule
xposeddescription
xposedminversion
xposedscope
```

删除：

```text
app/src/main/res/values/arrays.xml
```

将描述转为普通 Android application 属性，例如：

```xml
<application
    android:label="FeishuKit"
    android:description="@string/module_description">
```

新增对应 string 资源。

将：

```xml
android:minSdkVersion="22"
```

改为：

```xml
android:minSdkVersion="29"
```

`targetSdkVersion` 保持 34。

## 5. 旧元数据删除时机

阶段 1 可以先保留 `assets/xposed_init` 以便构建链对照，但在阶段 4 完成所有 modern 入口验证后必须删除：

```text
app/src/main/assets/xposed_init
```

最终 APK 不能再包含它。

## 阶段出口

检查 APK：

```powershell
jar tf feishu-antirecall.apk | Select-String 'META-INF/xposed|assets/xposed_init'
```

必须看到：

```text
META-INF/xposed/java_init.list
META-INF/xposed/module.prop
META-INF/xposed/scope.list
```

必须看不到：

```text
assets/xposed_init
```

同时确认构建输出中没有把 `io/github/libxposed/api/` 的 API 实现类打进模块 DEX。
