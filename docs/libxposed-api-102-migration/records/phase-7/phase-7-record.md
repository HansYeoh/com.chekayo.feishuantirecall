# 阶段 7 执行记录：真机回归

- 日期：2026-10-06
- 分支：`libxposed-api-102-migration`（基线：阶段 6 审计入档 commit `7b1583c`）
- 执行文档：[08-device-regression.md](../../08-device-regression.md)
- 模块安装包：阶段 6 产物原包直接安装（**未重新构建**），`feishu-antirecall.apk` 1537004 字节，SHA-256 `0bd9786b53dc7dfacd1e78dac39c6fc228368e812747073cc6606b85e6a132af`，签名证书 `7c20f829…`（与基线/EXPECTED_SIG 一致），versionName 1.8.9 / versionCode 31 未动。

## 0. 环境信息（详见 device-env.txt）

| 项 | 值 |
|---|---|
| 设备 | Xiaomi 15 Pro（2410DPN6CC），HyperOS，Android 17 / SDK 37 |
| Root / 框架 | KernelSU + LSPosed v2.2.1 (7912, zygisk_lsposed) |
| 目标应用 | `com.ss.android.lark` 飞书 **8.1.12**（8011250），targetSdk 35 |
| 国际版 `com.larksuite.suite` | 未安装 → 按 08 文档出口条款**记录未测试** |
| 模块状态 | LSPosed 自动识别 modern 元数据，`modules_state.enabled=1`（user 0），scope=`[com.ss.android.lark]`（全新安装沿用旧启用记录，scope 未改动） |

## 1. 框架加载测试（08 §1）

| 编号 | 测试 | 结果 | 证据 |
|---|---|---|---|
| F01 | 模块安装 | **PASS** | adb install Success；LSPosed 读 `META-INF/xposed/` 三件套识别为 modern 模块（若不支持 API 102 不会列出/加载） |
| F02 | 作用域 | **PASS**（国内版） | scope 含 `com.ss.android.lark`；`staticScope=true` 下国际版包名在 APK 元数据中（阶段 6 已验），设备未装国际版记未测试 |
| F03 | 重启目标应用 | **PASS** | `ModuleRuntime: bound … api=102 framework=LSPosed 2.2.1`（=onModuleLoaded 链）+ `ModulePath: MODULE_PATH = /data/app/…/base.apk` + `onPackageLoaded`（仅日志）+ `onPackageReady: dispatch`（唯一完整分发）日志齐全 |
| F04 | 重启多次 | **PASS** | 3 次重启每次恰 2 进程 / 2 次分发 / hook 数恒定 39+37 / `skip duplicate` 0 条 / FATAL 0 条 |
| F05 | 模块自身设置页 | **PASS** | `LauncherActivity` 成功置顶（topResumedActivity），无崩溃；模块自身进程不走模块加载路径，ModuleLog 安全降级，不依赖 legacy API |
| F06 | 老框架设备 | **N/A** | 测试设备本身就是 modern 框架，无老框架对照环境；删除 legacy 元数据后老框架不加载属 README §2 预期代价 |

生命周期日志原文见 [lifecycle-f03-f04.log](lifecycle-f03-f04.log)（restart1 全量模块行）。

## 2. 进程覆盖测试（08 §2）

restart3 时点逐进程记录：

| process | package | ClassLoader identity(dispatch key 第3段) | installed hooks | native started | config bridge |
|---|---|---|---|---|---|
| `com.ss.android.lark`（主进程） | com.ss.android.lark | 205258361 | 39 | **是**：antirecall（wcdb2+sqlcipher 双库 sqlite3_step）+ resign（sqlcipher prepare_v2），`antirecall-inst`/`lark-resign-tra` 线程存活 | bound |
| `com.ss.android.lark:wschannel`（推送/websocket） | com.ss.android.lark | 207587393 | 37 | 否（设计如此：native SQL 层 hook 仅主进程装载） | bound |

- **设置页 / 资料页进程归属**：飞书 8.1.12 无独立设置/资料进程，两页面归属主进程——对应 hook（`SettingPageFragment#onResume`、`UserProfileActivityV3#onCreate`）已装在主进程（日志在案）。
- **webview 包回调**：主进程内加载 `com.google.android.webview` 时仅触发 `onPackageLoaded`（log-only），未分发业务、未导致 configbridge 重复安装（每进程恰 1 次 install + 1 次 bound）——验证了阶段 3「loaded 只记日志、Ready 才分发」设计。
- 模块自身进程 `com.chekayo.feishuantirecall`（LauncherActivity + ConfigProvider）不在门控/分发范围（设计如此）。
- 设备上未出现其他飞书进程；`scope.list` 两包名外的进程均未注入（未观察到非预期进程加载模块）。

## 3. 功能回归矩阵（08 §3）

**本阶段机器侧完成的是「安装层」验证**：全部 14 项功能对应的 hook/native 链路均已成功安装并有日志在案。**行为层**（真实收发消息、撤回、已读等）按 08 文档前置条件「由用户本人操作测试小号」，留用户执行后补录本节。

| 功能 | 机器侧证据（安装层） | 行为层 |
|---|---|---|
| 防撤回 | `recallui.setText`×2 + antirecall native SQL 层（wcdb2/sqlcipher 双库）hook installed | 待用户（测试小号发送→撤回→重进会话） |
| 后台消息存档 | `notifarchive.notify`（NotificationManager#notify）installed | 待用户（收通知后撤回，查存档路径） |
| 防已读 | `antiread2.readreq`×2 / `sendreq`×6（hookAllConstructors）installed | **已验证**：浏览抑制正常（见 §3.1） |
| 回复已读窗口 | 同上 sendreq 系列 installed | **已验证（带载体局限）**：见 §3.1 |
| 去水印 | `dewatermark.setForeground` installed | 待用户 |
| AI 速览屏蔽 | `aipeek.addView`×4 + `setVisibility` + `setText`×2 installed | 待用户（会话+搜索页） |
| 下载解锁 | `dlunlock.fileopen` + `downloadcheck` installed | 待用户（加密图片/文件下载） |
| 保密模式 | `restricted.getSwitch` + `interceptor`×3 installed | 待用户（复制/转发） |
| 强制截图 | `forcescreenshot.setFlags/addFlags/setAttributes/setSecure` installed | 待用户（截图/录屏） |
| 审计屏蔽 | `restricted.writeData` + `restricted.auditManager` installed | 待用户（复制/下载/截图后看审计落库） |
| 设置入口 | `fucklarksettings.settingpage` installed；模块卡片待用户目视确认 | 待用户 |
| ProfileCapture | `profilecapture.onCreate` installed | 待用户（打开资料页） |
| 离职统计 | native dump 全链路真机跑通：离职快照 59 行、V3 富资料 369 行、全量花名册 660 行，JSON 落盘 `files/accounts/<uid-hash>/resign_tracker/`（路径按目标包+账号隔离） | 已由 native 自动完成，用户可核对数据页展示 |
| 下载镜像 | `dlmirror.mustacheFormat` installed + FileObserver 已监听 `Android/data/com.ss.android.lark/files/Lark/download` | 待用户（完成一次下载看公共 Download 复制） |

### 3.1 防已读 / 回复已读窗口 行为层验证（2026-10-06 01:24~01:35，真机实测三轮）

背景：用户与测试小号对测（小号为普通客户端，测试手机为装模块的工作账号设备；小号另用桌面端观察对侧视角）。机器侧后台采集 `LSPosedFramework`/`antiread-j` 行（`build/phase7-device/live-antiread-test.log`，本地留存）。

- **浏览抑制（防已读本体）PASS**：测试手机浏览小号消息产生的全部 `UpdateMessagesMeReadRequest` 均走 `清空(浏览,暂存)` 分支（READ_REQ #2~#8、#10~#21，ids 全部 N->0），小号侧持续显示未读——功能生效。
- **回复窗口放行链路 PASS（端到端）**：`01:32:44 READ_REQ #9 ids:0->9 maxPos=38 sendWin=true 放行(回复,补9条)`——回复瞬间开窗 → 飞书发出回复时已读推送（0 ids + maxPos，与代码注释模型一致）→ 模块把暂存 9 条 ids 合并放行 → 小号侧对应消息变已读。拦截/暂存/开窗/合并/放行五环在 API 102 迁移版上全部实测工作。
- **载体局限（8.1.12 实测，非迁移回归，属模块与飞书行为的交互效应）**：基线对照轮（`Config.antiread=false` 仅记录模式，app 已读行为原生）显示**原生 8.1.12 的回复时已读推送是每条回复都发的**（`READ_REQ #29/#35/#36 ids:0->0 maxPos=66/72/73 sendWin=true 仅记录`，30 秒连续对话内 3 次）；而模块开启时载体仅在进入会话后的第一次发送出现（#9、#25 两次独立命中，之后窗口内零载体）。即模块清空浏览请求这一行为本身改变了飞书后续发送载体的条件（确切内部触发机制在请求层面无法进一步定位，属飞书读同步逻辑）。legacy 与迁移版机制逐字一致、模块侧五环链路两次端到端实测存活，**迁移回归排除**。8.1.12 上功能的实际形态：浏览保持未读；进会话后第一次回复→积压全部补发已读；停留会话连续回复→对侧看到回复但消息仍显示未读，退出重进再回复即恢复。若上游希望改善（如回复时模块主动构造带暂存 ids 的读请求作为载体），属新适配工作，不在本次迁移范围。
- 第一轮测试（01:00 前后）失败原因：阶段 7 机器侧 F03/F04 验证的 force-stop 与用户测试窗口重叠，进程内存态 `PENDING_READ` 暂存被清——测试干扰，非代码问题；已留档避免复审误解。

## 4. Native 回归（08 §4）——全部 PASS

- `libantirecall.so` 从模块 APK 解出至 `/data/data/com.ss.android.lark/antirecall/`，nativeloader `ok`；`libresign.so` 解出至 `/data/user/0/com.ss.android.lark/resign_tracker_lib/`，加载成功。两 so 均为模块数据目录隔离存放。
- sqlite hook 安装日志正常：antirecall 对 `libwcdb2.so` 与 `libsqlcipher.so` 的 `sqlite3_step` 双库 hook installed（"after 0 tries"）；resign 对 `libsqlcipher.so` `sqlite3_prepare_v2` hook installed。
- native maintain 线程正常：installer 线程安装成功后转常驻，`/proc/<pid>/task` 实测 `antirecall-inst`、`lark-resign-tra` 存活。
- native 数据目录按目标包+账号隔离：`files/accounts/<uid-hash>/resign_tracker/{resigned_all,profiles}.json`。
- 无提前加载/重复加载：每进程单次 `JNI_OnLoad`（PV-MON 监控正常启动，initRegex 初始化在案）；hot reload 被拒后旧代未重复加载 native；重装换新代（61b1）后单次加载、hook installed 一次。
- 日志证据见 [native-regression.log](native-regression.log)。

## 5. Hot reload 门控回归（08 §5）——拒绝路径 PASS

**触发方式**：两个目标进程存活时（主进程 native 已闩 + wschannel 仅配置桥），`adb install -r` 同版本重装模块 → LSPosed 框架于 01:07:32 发起 Auto hot reload 协商。

1. **主进程拒绝**（原因与阶段 5 资源登记设计逐项一致）：
   > `hot reload rejected: runtime is not teardown-safe -> native=[antirecall.native-inline,resigntracker.native-inline] threads=[resigntracker.archive-push-thread,resigntracker.tracker-thread,antirecall.installer-thread,resigntracker.boot-thread] callbacks=[config-bridge.receiver(sync,pull),download-mirror.file-observer]`

2. **:wschannel 拒绝**：`callbacks=[config-bridge.receiver(sync,pull)]`——该进程确实只有这一个闩资源，验证了资源表**按进程精确记录**。
3. **框架确认拒绝**：`Class com.chekayo.feishuantirecall.FeishuKitModule refused to be hot reloaded, skipping` + 守护进程 `Auto hot reload failed … status=1`。
4. **目标进程不崩溃**：拒绝时点 PID 不变（01:07:40 复测 main=19213/ws=25111 与重装前一致），reload-test.log FATAL=0。随后进程被框架/系统按常规路径重启换新代（新 gen `61b1`，01:08:58），属「拒绝后框架落地更新」的正常语义，非崩溃。
5. **无 hook 重复叠加**：新代进程干净加载——dispatch 恰 1 次、`HookRuntime: installed` 恰 39 条、config bridge bound 1 条、`skip duplicate` 0 条；旧代随进程死亡消失，无跨代叠加。
6. 严格遵守 08 §5 警示：本机结果是 **refuse**，未发生完整热重载，不得表述为「模块已支持热重载」。

**allow 路径边界**：08 §5.1「未启动 native 的 Java-only 干净场景」在真机不可达——模块加载到的每个飞书进程至少闩 `config-bridge.receiver`（wschannel 即证），故真机只覆盖拒绝路径；放行路径由阶段 5 宿主 JVM 行为测试（49/49，含并发快照）覆盖。

日志原文见 [reload-gate.log](reload-gate.log)。

## 6. 日志采集与脱敏（08 §6）

- 归档仅含模块相关行（`LSPosedFramework` 模块行 + native 行 + 框架 reload 行），**完整 logcat dump 不入库**（原始 dumps 留在本地 `build/phase7-device/`，该目录 gitignore）。
- 脱敏项：账号 uid 哈希 → `<uid-hash>`；模块/目标 APK 安装路径随机段 → `…`；未含聊天正文、手机号、user/tenant ID、导出数据。
- 归档文件：[lifecycle-f03-f04.log](lifecycle-f03-f04.log)（161 行）、[reload-gate.log](reload-gate.log)、[native-regression.log](native-regression.log)、[device-env.txt](device-env.txt)；`*.log` 按惯例 `git add -f`。

## 阶段出口对照（08「阶段出口」）

| 出口条款 | 状态 |
|---|---|
| 国内版目标功能通过 | 机器侧（安装/生命周期/进程覆盖/native/reload 门控）全部通过；§3 行为矩阵 12 项待用户测试小号执行后补录 |
| 国际版 | 未安装，记录未测试 ✓ |
| 多进程无重复注册和崩溃 | ✓（3 次重启 + reload 拒绝 + 换代，零 FATAL、零 skip duplicate） |
| native loader 和配置桥正常 | ✓ |
| hot reload 安全拒绝逻辑正常 | ✓（真机真框架协商拒绝） |
| 所有失败项有复现步骤和日志摘要 | 无失败项 |

## 偏差与说明

1. **F06 N/A**：无老框架环境（非失败）。
2. **`screenshot-noaudit install failed: NoSuchMethodError gc6.a#onActivityResumed`**：两进程各 1 条。`gc6.a` 为飞书 7.70 专有混淆名（源码注释明示「仅 7.70 定位」），8.1.12 下类不存在，try/catch 降级为日志——legacy 版本同版本 app 下同样失败，属基线既有行为，非迁移回归。
3. **§3 行为层验证未在本记录内闭环**：按 08 前置条件由用户本人操作测试小号执行；本提交先落机器侧结果，行为矩阵执行后在本文件补录。
4. **reload 触发方式**：采用同版本 `adb install -r` 走 LSPosed Auto hot reload 路径（无 root 注入、无管理器 UI 自动化）；未测试「LSPosed 管理器手动 reload」入口（如有），属等价触发面。
5. ClassLoader identity（205258361/207587393）为进程内 identityHashCode，多次重启间出现同值属 ART 分配确定性，非固定 ID。

## 下一步

- 用户执行 §3 行为矩阵（测试小号）→ 结果补录本文件 → 阶段 7 关闭。
- 阶段 8：上游 PR（[09-upstream-pr.md](../../09-upstream-pr.md)）。
