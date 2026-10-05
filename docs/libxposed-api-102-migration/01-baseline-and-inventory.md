# 阶段 0：基线与迁移清单

## 目标

在任何源码迁移前冻结当前行为基线，并建立逐 hook 的迁移清单。此阶段不修改业务代码。

## 输入

- 当前 `main` 分支。
- 当前可构建的 `build.ps1` / `build.sh`。
- 当前 `feishu-antirecall.apk` 仅作为本地参考，不提交新的二进制产物。

## 1. 建立迁移分支

建议分支名：

```text
codex/libxposed-api-102
```

检查工作区：

```powershell
git status --short --branch
git log -5 --oneline --decorate
```

要求：

- 开始阶段工作区干净。
- 记录开始迁移的 commit SHA。
- 不覆盖当前已验证的 APK。

## 2. 构建并保存基线

Windows 环境执行：

```powershell
./build.ps1
```

保存以下信息到本阶段记录中：

- 构建是否成功。
- `versionCode` / `versionName`。
- APK SHA-256。
- 签名证书摘要。
- APK 中 `classes.dex`、`lib/arm64-v8a/`、`assets/` 的清单。
- 当前 APK 是否存在 `assets/xposed_init`。

如果本机暂时无法执行完整构建，必须记录失败阶段和原因，不得把“未构建”标成通过。

## 3. 生成 legacy 依赖清单

从仓库根目录执行：

```powershell
rg -n --hidden --glob '!*.apk' --glob '!*.idsig' `
  'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook|IXposedHook|xposed_init' `
  app stubs build.ps1 build.sh scripts
```

同时记录：

```powershell
rg -n 'XposedBridge\.hookMethod|XposedBridge\.hookAllMethods|XposedBridge\.hookAllConstructors|XposedHelpers\.findAndHookMethod' app/src/main/java
rg -n 'new XC_MethodHook|extends XC_MethodHook|beforeHookedMethod|afterHookedMethod' app/src/main/java
rg -n 'XposedBridge\.log' app/src/main/java
rg -n 'XposedHelpers\.' app/src/main/java
```

当前机械扫描口径约为：

- `findAndHookMethod`：23 处
- `hookMethod`：9 处
- `hookAllMethods`：6 处
- `hookAllConstructors`：2 处
- `XposedBridge.log`：122 处

如果人工统计的 35 个注册点、38 个回调类与机械统计不同，以逐项清单为准，不以总数作为唯一验收条件。

## 4. 建立 hook 清单

建议建立本地工作文件：

```text
build/migration/hook-inventory.tsv
```

每行至少包含：

```text
file	line	target	method	kind	before_after	mutates_args	short_circuit	mutates_result	dynamic_hook	notes
```

`kind` 取值建议：

- `findAndHookMethod`
- `hookMethod`
- `hookAllMethods`
- `hookAllConstructors`

`before_after` 取值建议：

- `before`
- `after`
- `mixed`

特别标记以下高风险点：

- 直接写入 `p.args[...]` 的 hook。
- 调用 `p.setResult(...)` 的 hook。
- 使用 `p.getResult()` 的 after-hook。
- after-hook 中继续安装新 hook 的动态 hook。
- constructor hook。
- Android framework 类 hook。
- 依赖 `MODULE_PATH` 的 native loader。

## 5. 冻结不改动边界

在迁移前记录以下目录的 Git 状态：

```text
native/jni/
配置相关 Java 类和 JSON 文件
数据文件读写逻辑
```

迁移完成后必须执行：

```powershell
git diff -- native/jni
```

结果应为空。

## 阶段出口

满足以下条件才进入阶段 1：

- 当前版本至少成功构建过一次，或构建失败原因已明确记录。
- hook 清单已逐项建立。
- native/config/data 不改动边界已确认。
- 迁移分支和基线 commit 已记录。
