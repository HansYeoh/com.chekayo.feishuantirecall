# 阶段 3：单一入口与生命周期分发

## 目标

把原来的 4 个 legacy 入口合并成一个 modern API 102 入口，并保证每个目标进程只执行一次完整安装流程。

## 1. 新入口

新增：

```text
app/src/main/java/com/chekayo/feishuantirecall/FeishuKitModule.java
```

结构：

```java
public final class FeishuKitModule extends XposedModule {
    @Override
    public void onModuleLoaded(ModuleLoadedParam param) { ... }

    @Override
    public void onPackageReady(PackageReadyParam param) { ... }

    @Override
    public boolean onHotReloading(HotReloadingParam param) { ... }

    @Override
    public void onHotReloaded(HotReloadedParam param) { ... }
}
```

不实现 `onSystemServerStarting()`，因为当前模块没有 system server 业务，也不应把作用域扩展到 system server。

## 2. `onModuleLoaded`

只负责进程级初始化：

1. 绑定 `ModuleRuntime`。
2. 读取当前进程名。
3. 读取模块 APK `sourceDir`。
4. 设置 `AntiRecall.MODULE_PATH`。
5. 设置 `ResignTracker.MODULE_PATH`。
6. 初始化本代的 hook registry。
7. 记录 modern API 和 framework 信息。

不能在此处安装依赖目标应用 ClassLoader 的业务 hook，因为此回调没有目标包 ClassLoader。

## 3. `onPackageReady`

作为唯一完整业务分发入口：

1. 取得 `packageName`。
2. 取得真实 `ClassLoader`。
3. 检查是否为国内版飞书、国际版 Lark 或可识别的白标应用。
4. 检查当前进程是否满足该功能的进程限制。
5. 通过 `ModuleRuntime` 设置当前包和 ClassLoader。
6. 以幂等方式分发功能安装。

分发目标：

```text
AntiRecall
ResignTracker
FuckLarkSettings
ProfileCapture
```

## 4. 不要双重分发

禁止以下结构：

```java
onPackageLoaded -> installAll()
onPackageReady  -> installAll()
```

推荐：

```java
onPackageLoaded -> 只做轻量记录，或不实现
onPackageReady  -> installAll()
```

如果未来某个功能确实必须在 `onPackageLoaded` 阶段安装，应为它单独定义一次性状态，不得重新调用完整入口。

## 5. 包和进程过滤

modern API 的 scope 只决定注入范围，进程中仍可能加载多个 package。因此入口必须继续保留现有过滤逻辑：

- `com.ss.android.lark`
- `com.larksuite.suite`
- `AntiRecall.isLarkApp(classLoader)` 白标探测
- 主进程限制
- 后台通知进程允许安装的功能

不要因为现代 API scope 已经过滤，就删除业务层的 package/process 判断。

## 6. 幂等键

建议使用以下组合判断是否已分发：

```text
processName
packageName
ClassLoader identity
```

不要只依赖类中的全局 `INSTALLED` 布尔值，因为：

- 一个进程可能加载多个 package。
- `onPackageLoaded`/`onPackageReady` 是不同阶段。
- hot reload 会产生新的 module generation。
- 不同进程拥有各自的静态状态。

## 7. 4 个旧入口的迁移映射

| 旧入口 | modern 处理 |
|---|---|
| `AntiRecall` | 保留功能类，删除 legacy interface，实现由 `FeishuKitModule` 分发 |
| `ResignTracker` | 保留功能类，删除 legacy interface，由单入口分发 |
| `FuckLarkSettingsHook` | 删除包装入口，直接调用 `FuckLarkSettings` 安装逻辑 |
| `ProfileCapture` | 保留功能类，删除 legacy interface，由单入口分发 |

## 阶段出口

- APK 只注册一个 Java 入口。
- 每个目标进程的完整分发只发生一次。
- `MODULE_PATH` 在业务 hook 安装前已经设置。
- 国内版、国际版和白标判断逻辑没有被削弱。
- 没有任何 legacy lifecycle interface。
