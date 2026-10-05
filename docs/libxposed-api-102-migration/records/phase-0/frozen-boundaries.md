# 阶段 0 记录 C：不改动边界冻结

- **日期**：2026-10-05
- **冻结基线**：commit `e61b868`，`git status` 干净（仅本记录目录为未跟踪新增）
- **用途**：迁移全部完成后按本文件核对，验证命令见第 4 节

## 1. native/jni/ —— 整目录零改动（P0 验收项）

`git ls-files -s native/jni`（blob SHA 即内容指纹，迁移后应逐一相同）：

| blob SHA | 文件 |
|---|---|
| 53617b7a2fc5051fe93801c551c7821a7953fb36 | native/jni/And64InlineHook.cpp |
| 7af50904e6ebbe2c5dad8936a2b1418f6843c33d | native/jni/And64InlineHook.hpp |
| 0b1a828fb23117be1b0cd91d7b4023c7c3527db3 | native/jni/antirecall.cpp |
| f58014d6afbc393310e47f80954c52d7405323b2 | native/jni/resign.cpp |

约束：`native/jni/` 下所有文件的 diff 必须为空；JNI 方法名（`tryInstall`/`nativeMaintain`/`nativeLog`/`nativeSetRecall`/`nativeSetDiag`/`nativeSetKeepKicked`/`nativeSetLeaveNotify`/`nativePollLeaveEvent`/`nativeSetDataDir`/`nativeInit`/`nativeArmDump`/`nativePoll`/`nativeArmProfiles`/`nativeProfileResult`/`nativeArmRoster`/`nativeRosterResult`）与签名不得变更。

## 2. 配置与数据读写相关 Java 类（行为语义零改动）

这些类允许随迁移调整 import/编译适配，但配置 JSON 语义、数据文件/JSONL 格式、数据库路径推导逻辑不得变化：

| blob SHA | 文件 | 职责 |
|---|---|---|
| 165f18ed6a759a861389755a095c6665798d4f5b | app/src/main/java/com/chekayo/feishuantirecall/Config.java | 配置装载/同步/权威源 |
| e76838095f56508045f3441e9c71994df6c1a838 | app/src/main/java/com/chekayo/feishuantirecall/ConfigProvider.java | 跨进程配置桥 provider |
| 90de95f83fc423e0650fc9cdbb908a3aa59bb21e | app/src/main/java/com/chekayo/feishuantirecall/AccountPaths.java | 账号目录推导/uid 探测/旧数据迁移 |
| cb4b0a3bb1544863ecb7b57da1c0cf61aba77597 | app/src/main/java/com/chekayo/feishuantirecall/NotifArchive.java | 通知存档 + 撤回还原表 |
| 8fe30933df8c563b85a7b676f6bc63d42f2ee640 | app/src/main/java/com/chekayo/feishuantirecall/ArchiveSync.java | 桌面/飞书进程档案副本推送 |
| 9ed4fa1819480f7f0cbab7fd3697c4645837ba6a | app/src/main/java/com/chekayo/feishuantirecall/ProfileBulk.java | 花名册/富资料 JSONL 合并 |
| b7d6782847935a9dd0cc2cfb9442bfa8a2e891a7 | version.json | 更新通道元数据（本次明确不改） |

数据路径语义基线（不得变更）：`/data/data/<宿主包>/files/accounts/<uid>/` 下的 `resign_tracker/profiles.json`、`resigned_all.json`、`resigned_latest.json`、`v3_bulk.jsonl`、`roster.jsonl`，以及 `/data/data/<宿主包>/antirecall/`（so 释放目录）、`notif_archive` 相关文件、`Diag` 诊断日志。

## 3. 其他冻结项

- `MODULE_PATH` 静态字段语义：`AntiRecall.MODULE_PATH` 被 DataViews 反射读取（`DataViews.moduleApkPath()`），被 native loader（`startNative`/`extractSo`）与签名自校验使用 —— 迁移后必须仍指向模块 APK 实际路径。
- 版本常量：`AntiRecall.MODULE_VERSION="1.8.9"` / `MODULE_VERSION_CODE=31` / Manifest versionCode/versionName 本次全部不变（pr_gate.sh G7 的问题由维护者处理，见计划第 3.6 节）。
- 进程级 hook 范围：主进程专属逻辑（native 层、DownloadMirror、ResignTracker）与全进程逻辑（通知存档、去水印、UI 拦截等）的进程判定不得扩大/缩小。

## 4. 迁移完成后的核对步骤

```powershell
git diff -- native/jni          # 必须为空
git status --short              # 上述冻结文件不应出现（或出现但 diff 为纯 import/编译适配，需逐行审计）
git diff --stat -- app/src/main/java/com/chekayo/feishuantirecall/Config.java app/src/main/java/com/chekayo/feishuantirecall/ConfigProvider.java app/src/main/java/com/chekayo/feishuantirecall/AccountPaths.java app/src/main/java/com/chekayo/feishuantirecall/NotifArchive.java app/src/main/java/com/chekayo/feishuantirecall/ArchiveSync.java app/src/main/java/com/chekayo/feishuantirecall/ProfileBulk.java version.json
```
