# 阶段 2：现代运行时桥接层

## 目标

建立一层稳定的模块运行时，让业务功能类不需要直接理解 API 102 的生命周期细节，同时彻底摆脱 legacy API。

## 建议新增文件

```text
app/src/main/java/com/chekayo/feishuantirecall/ModuleRuntime.java
app/src/main/java/com/chekayo/feishuantirecall/HookRuntime.java
app/src/main/java/com/chekayo/feishuantirecall/Reflect.java
app/src/main/java/com/chekayo/feishuantirecall/ModuleLog.java
app/src/main/java/com/chekayo/feishuantirecall/ModulePath.java
```

具体命名可以调整，但职责不要混杂。

## 1. ModuleRuntime

保存当前 modern 模块实例和当前进程上下文：

- `XposedModule` / `XposedInterface` 实例。
- 当前进程名。
- 当前包名。
- 当前 ClassLoader。
- 当前模块 APK 的 `sourceDir`。
- 当前 module generation 的唯一标识。

不要让业务类自行缓存多个 `XposedModule` 实例。

## 2. ModuleLog

替换全部：

```java
XposedBridge.log(message)
```

统一入口：

```java
ModuleLog.log(message)
```

要求：

- 保持原有日志正文不变。
- 默认使用 protective 级别。
- 统一固定 tag。
- 允许在模块尚未绑定或运行于模块自身进程时安全降级。
- 不在业务代码中直接调用 `XposedModule.log()`。

当前代码只有字符串日志调用，不需要为了迁移额外改造异常日志格式。

## 3. Reflect

替代当前实际使用的 XposedHelpers 功能：

- `findClass(String, ClassLoader)`。
- `callMethod(Object, String, Object...)`。
- `callStaticMethod(Class, String, Object...)`。
- `getStaticObjectField(Class, String)`。
- 精确方法查找。
- 根据签名枚举 declared methods / constructors。

实现要求：

- 优先使用传入目标应用 ClassLoader。
- 处理父类字段和父类方法。
- 处理 primitive boxing。
- 正确处理 null 参数。
- 反射失败抛出原始异常或带目标类/方法信息的异常。
- 不通过字符串反射调用任何 `de.robv` 或 legacy API。

## 4. HookRuntime

替代：

```text
XposedBridge.hookMethod
XposedBridge.hookAllMethods
XposedBridge.hookAllConstructors
XposedHelpers.findAndHookMethod
```

API 102 的核心调用是：

```java
module.hook(executable).intercept(chain -> {
    return chain.proceed();
});
```

HookRuntime 需要提供：

- 对 `Method` 的 hook。
- 对 `Constructor` 的 hook。
- 按方法名 hook 全部 declared methods。
- 按构造函数 hook 全部 declared constructors。
- 按参数类型精确查找方法。
- 注册并保存每个 `HookHandle`。
- 以 `process + package + ClassLoader + logicalHookId` 防止重复安装。

不要在每个业务类里各自维护一套重复检测逻辑。

## 5. HookHandle 注册

即使第一版主进程暂时不能安全 hot reload，也应在注册时保存 `HookHandle`，否则后续无法实现：

- Java-only hook 的卸载。
- API 102 的同 ID 原子替换。
- reload 诊断。
- 测试阶段的重复安装检测。

建议每个 handle 记录：

```text
logical id
process name
package name
ClassLoader identity
Executable signature
install timestamp
```

## 6. ModulePath

API 102 不提供 legacy 的 `initZygote(StartupParam.modulePath)`。

在 `onModuleLoaded()` 中使用：

```java
getModuleApplicationInfo().sourceDir
```

并写入现有功能仍然依赖的路径字段：

```text
AntiRecall.MODULE_PATH
ResignTracker.MODULE_PATH
```

不得修改 native loader 的 APK 内路径约定：

```text
lib/arm64-v8a/libantirecall.so
lib/arm64-v8a/libresign.so
```

## 阶段出口

- 新桥接层只依赖 `io.github.libxposed.api` 和 Android SDK。
- 不依赖任何 `de.robv` 类型。
- 可由一个最小 modern 入口编译通过。
- HookHandle 能被统一记录。
- `MODULE_PATH` 能从 module application info 获得。
