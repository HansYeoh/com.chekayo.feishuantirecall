# 阶段 8：上游 PR 准备

## 目标

在本机验证完成后，整理成适合上游维护者审阅和合并的提交与 PR。

## 1. 推荐 commit 组织

开发过程中可以拆成：

```text
build: vendor libxposed api 102 and modern metadata
refactor: add modern module runtime and hook bridge
refactor: migrate feature hooks to libxposed interceptor chain
refactor: migrate settings profile and resign entrypoints
cleanup: remove legacy xposed entrypoints and metadata
docs: document modern api 102 and android 10 requirements
```

如果中间 commit 为了迁移顺序暂时保留 legacy 类型，提交 PR 前应确认每个保留的中间 commit 是否可独立构建。若不适合上游逐个审阅，可以在本地保留拆分，PR 前 squash 成一个最终迁移 commit。

## 2. PR 中必须说明

- 这是 legacy API 到 libxposed API 102 的单模迁移。
- 不再兼容旧版 legacy 框架。
- 有效 Android 下限提升到 Android 10。
- native 源码没有改动。
- 配置和数据格式没有改动。
- 版本号暂时保持 1.8.9，由维护者决定 release bump。
- `autoHotReload` 已声明，但当前 native/线程运行态采用安全拒绝策略，不宣称完整 native hot reload。
- 已完成的真机版本、设备、Android 版本和框架版本。
- 未完成的功能或环境必须明确列出。

## 3. 版本号门禁说明

当前：

```text
scripts/pr_gate.sh
```

中的 G7 要求 PR diff 出现：

- Manifest 版本号。
- `AntiRecall` 版本常量。
- `version.json` 版本号。
- 新 versionCode 大于当前 Release。

本次产品决策是不改版本号，因此该 gate 按当前规则会失败。处理方式建议按以下优先级：

1. 先在 PR 描述中说明这是基础设施迁移，不负责 release bump。
2. 请求维护者手工审阅或在合并前补版本提交。
3. 如果上游接受，另开 release/version PR。
4. 不要为了机械通过 gate 私自把 1.8.9 改成 1.9.0。

## 4. PR 前最终检查

```powershell
git status --short
git diff --check
git diff --stat
git diff -- native/jni
rg -n --hidden --glob '!*.apk' --glob '!*.idsig' `
  'de\.robv|XposedBridge|XposedHelpers|XC_MethodHook|IXposedHook|xposed_init' `
  app stubs build.ps1 build.sh
```

确认：

- native diff 为空。
- 没有 legacy 源码引用。
- 没有未跟踪的真实数据或目标 APK。
- 没有签名私钥。
- 没有把 APK、日志、反编译产物加入提交。
- `version.json` 未被意外修改。

## 5. PR 描述模板

```markdown
## Summary

- Migrate the module from legacy Xposed API to libxposed API 102.
- Replace four legacy entrypoints with one `XposedModule` entry.
- Convert legacy hooks to interceptor-chain hooks.
- Remove `assets/xposed_init` and legacy manifest metadata.

## Compatibility

- Requires a framework implementing libxposed API 102.
- Effective Android version: Android 10+.
- Legacy Xposed frameworks are intentionally unsupported.

## Behavior preservation

- Native sources unchanged.
- Configuration and data formats unchanged.
- Anti-recall, anti-read, watermark, audit, download and archive behavior migrated without intended semantic changes.

## Validation

- Local Windows build: PASS/FAIL
- Local Linux build: PASS/FAIL/NOT RUN
- APK metadata inspection: PASS/FAIL
- Legacy reference scan: PASS/FAIL
- Android device / version:
- Framework version:
- Domestic Feishu: PASS/FAIL
- International Lark: PASS/FAIL/NOT INSTALLED
- Hot reload safety gate: PASS/FAIL

## Versioning

Version remains unchanged intentionally. Release version bump is left to the maintainer.

## Known limitation

The first version rejects hot reload when native hooks, module threads, or external callbacks are active because complete teardown is not part of this migration.
```

## 阶段出口

- commit 历史可读。
- PR 描述完整。
- 测试证据已脱敏。
- 版本号策略已向维护者说明。
- 工作区不包含构建产物和敏感文件。
