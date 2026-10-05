# 阶段 4：逐点迁移 hook 注册与回调

## 目标

把所有 legacy hook 转成 API 102 interceptor-chain，保持每个 hook 的业务条件、参数修改、短路返回和 after-hook 行为等价。

## 1. 统一转换原则

### 1.1 普通继续执行

旧逻辑：

```java
// afterHookedMethod 中不改结果
```

modern：

```java
return chain.proceed();
```

### 1.2 短路返回

旧逻辑：

```java
if (condition) {
    p.setResult(false);
}
```

modern：

```java
if (condition) {
    return false;
}
return chain.proceed();
```

### 1.3 参数修改

API 102 的 `chain.getArgs()` 是不可变列表，不能直接写：

```java
chain.getArgs().set(0, value); // 错误
```

正确模式：

```java
Object[] args = chain.getArgs().toArray();
args[0] = value;
return chain.proceed(args);
```

### 1.4 after-hook 修改结果

旧逻辑：

```java
Object result = p.getResult();
// 根据 result 继续处理
p.setResult(newResult);
```

modern：

```java
Object result = chain.proceed();
// 根据 result 继续处理
return newResult;
```

### 1.5 异常

当前代码没有依赖 `getThrowable()` / `setThrowable()` 的业务分支，但迁移时仍须检查每个 after-hook。

如果某个 hook 必须在原方法抛异常后继续执行，需要显式设计 try/catch；不能假设 `chain.proceed()` 会像 legacy 的 `afterHookedMethod` 一样把异常包装在参数对象里。

## 2. 按风险分组迁移

### 第一组：简单 before 短路

优先迁移：

- `AiPeekBlock`
- `RestrictedModeUnlock`
- `FileDownloadUnlock`
- `DataMigration`
- `AntiRecall` 中的水印和参数清理 hook

验收重点：

- 条件为 false 时一定调用原方法。
- 条件为 true 时原方法一定不调用。
- 返回值类型和 void 语义保持一致。

### 第二组：after-only UI/通知 hook

迁移：

- `FuckLarkSettings`
- `ProfileCapture`
- `UpdateBanner`
- `DownloadMirror`
- `AntiRecall.installNotifHook()`
- 撤回 UI 还原逻辑

验收重点：

- 原方法仍然先执行。
- `thisObject` 映射正确。
- delayed Runnable 的行为不变。
- 结果对象读取时没有被错误替换成 null。

### 第三组：动态和批量 hook

迁移：

- `hookAllMethods`
- `hookAllConstructors`
- 根据返回对象类动态继续 hook
- 通过签名枚举方法的逻辑
- 审计屏蔽动态目标

验收重点：

- `getDeclaredMethods()` / `getDeclaredConstructors()` 语义与旧实现一致。
- synthetic/bridge 方法过滤保持一致。
- 动态发现的实现类只安装一次。
- 目标 ClassLoader 来自当前目标应用，而不是模块自身。

### 第四组：AntiRecall 核心 hook

迁移：

- `Sdk.invoke*`
- read request constructor
- send request constructor
- mapper hook
- `Instrumentation.callApplicationOnCreate`
- native 启动前后的 Java hook

验收重点：

- `message_ids`、`fold_ids` 的清空逻辑不变。
- `ANTIREAD_DROP` 行为不变。
- mapper 的缓存和状态恢复逻辑不变。
- native 启动仍只发生在主进程。

### 第五组：ResignTracker

迁移重点：

- 入口参数由 `LoadPackageParam` 改为 package/classloader 信息。
- `MODULE_PATH` 改为由 `ModuleRuntime` 注入。
- 后台线程、nativeInit、数据合并逻辑不改变。

## 3. 逐文件迁移顺序

建议按以下顺序提交和验证：

1. `AiPeekBlock.java`
2. `DataMigration.java`
3. `RestrictedModeUnlock.java`
4. `FileDownloadUnlock.java`
5. `DownloadMirror.java`
6. `UpdateBanner.java`
7. `ProfileCapture.java`
8. `FuckLarkSettings.java`
9. `AntiRecall.java`
10. `ResignTracker.java`
11. 删除 `FuckLarkSettingsHook.java`

每完成一个文件，执行：

```powershell
rg -n 'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook' <file>
```

并进行一次 javac/d8 编译，避免最后集中处理几十个错误。

## 4. 业务等价检查表

每个 hook 完成后填写：

```text
[ ] 目标类仍由目标应用 ClassLoader 加载
[ ] 参数顺序没有改变
[ ] 参数修改改用 proceed(newArgs)
[ ] 短路时没有调用原方法
[ ] 原方法返回值仍然被正确传递
[ ] after-hook 逻辑执行时机不变
[ ] 动态 hook 没有重复注册
[ ] 原日志文本保留
[ ] 原 catch/ignore 行为保留
[ ] 主进程/后台进程限制保留
```

## 5. 禁止的迁移方式

禁止：

- 只做 import 替换，不检查 `p.args` 的不可变语义。
- 只把 `p.setResult()` 删除，不补返回值。
- 把所有 after-hook 粗暴改成 before-hook。
- 用模块自身 ClassLoader 查找飞书混淆类。
- 用一个全局 `INSTALLED` 替代进程/包/ClassLoader 维度的幂等控制。
- 为了编译通过修改业务判断或 native JNI 名称。

## 阶段出口

- 所有 hook 注册均通过现代 HookRuntime。
- 源码无 `XC_MethodHook`、`XposedBridge`、`XposedHelpers`。
- 逐项 hook 清单全部标记完成。
- 业务条件、短路、参数修改和返回值行为均完成代码审阅。
