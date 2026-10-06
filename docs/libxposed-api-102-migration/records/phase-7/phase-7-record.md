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

## 3. 功能回归矩阵（08 §3）—— 两轮行为测试全部执行完毕

**安装层**（hook/native 链路成功安装）由机器侧完成；**行为层**由用户本人操作工作号+测试小号分两轮执行：第一轮防已读专项 01:24~01:35（§3.1，含基线对照轮），第二轮其余各项 02:00~02:30（§3.2，期间持续 logcat 抓取 52 万行，**0 FATAL**）。火点证据见 [behavior-evidence.log](behavior-evidence.log)。

| 功能 | 机器侧证据（安装层） | 行为层结果 |
|---|---|---|
| 防撤回 | `recallui.setText`×2 + antirecall native SQL 层（wcdb2/sqlcipher 双库）hook installed | ✓ 用户第一轮实测：小号发送→撤回→重进会话，原文还原 |
| 后台消息存档 | `notifarchive.notify`（NotificationManager#notify）installed | ✓ 用户第一轮实测：收通知后撤回，存档正确 |
| 防已读 | `antiread2.readreq`×2 / `sendreq`×6（hookAllConstructors）installed | ✓ §3.1 + §3.2：READ_REQ 浏览读全部 `清空(浏览,暂存)`，小号侧持续未读 |
| 回复已读窗口 | 同上 sendreq 系列 installed | ✓ 放行链路端到端 PASS（§3.1：#9 暂存 9 条合并放行，小号侧变已读）；载体局限见 §3.1 |
| 去水印 | `dewatermark.setForeground` installed | ✓ 用户目视：图片/文件预览无姓名工号水印（静默 hook 无触发日志） |
| AI 速览屏蔽 | `aipeek.addView`×4 + `setVisibility` + `setText`×2 installed | ✓ 用户目视：会话不出现 AI 速览条、搜索卡片不误伤；命中日志 0 条（命中日志 800ms 节流且本轮未产生 peek 目标），以安装层+目视为准 |
| 下载解锁 | `dlunlock.fileopen` + `downloadcheck` installed | ✓ 下载并打开正常；打开文件详情页时 after 回调动态挂上 3 个审计上报方法（§3.2，动态链路真机验证） |
| 保密模式 | `restricted.getSwitch` + `interceptor`×3 installed | ⚠️ 部分恢复（§3.2 发现 1）：复制 ✓ 下载 ✓；转发被第二道策略门拦截，与 legacy 行为等价，非迁移回归 |
| 强制截图 | `forcescreenshot.setFlags/addFlags/setAttributes/setSecure` installed | ✓ 截图正常出画面（静默改参 hook 无触发日志） |
| 审计屏蔽 | `restricted.writeData` + `restricted.auditManager` installed | ✓ 操作全程正常；总闸双保险在 8.1.12 实际类上挂载成功（writeData#0 + auditManager#0）；诊断日志未开故无 `已拦下` 行 |
| 设置入口 | `fucklarksettings.settingpage` installed | ✓ 模块卡片可见、可打开进模块设置页；「高级设置卡片已注入」日志 ×4 |
| ProfileCapture | `profilecapture.onCreate` installed | ✓ `归档资料` ×8 生效，多目标按 uid 去重合并（累计递增），日志已整体脱敏 |
| 离职统计 | native dump 全链路真机跑通：离职快照 59 行、V3 富资料 369 行、全量花名册 660 行，JSON 落盘 `files/accounts/<uid-hash>/resign_tracker/`（路径按目标包+账号隔离） | ✓ 本轮观察到两个账号（工作号+小号）各自独立目录与快照，账号隔离真机验证 |
| 下载镜像 | `dlmirror.mustacheFormat` installed + FileObserver 已监听 `Android/data/com.ss.android.lark/files/Lark/download` | ✓ `[dl] 已另存到系统下载` ×1，公共 Download 出现副本 |

### 3.1 防已读 / 回复已读窗口 行为层验证（2026-10-06 01:24~01:35，真机实测三轮）

背景：用户与测试小号对测（小号为普通客户端，测试手机为装模块的工作账号设备；小号另用桌面端观察对侧视角）。机器侧后台采集 `LSPosedFramework`/`antiread-j` 行（`build/phase7-device/live-antiread-test.log`，本地留存）。

- **浏览抑制（防已读本体）PASS**：测试手机浏览小号消息产生的全部 `UpdateMessagesMeReadRequest` 均走 `清空(浏览,暂存)` 分支（READ_REQ #2~#8、#10~#21，ids 全部 N->0），小号侧持续显示未读——功能生效。
- **回复窗口放行链路 PASS（端到端）**：`01:32:44 READ_REQ #9 ids:0->9 maxPos=38 sendWin=true 放行(回复,补9条)`——回复瞬间开窗 → 飞书发出回复时已读推送（0 ids + maxPos，与代码注释模型一致）→ 模块把暂存 9 条 ids 合并放行 → 小号侧对应消息变已读。拦截/暂存/开窗/合并/放行五环在 API 102 迁移版上全部实测工作。
- **载体局限（8.1.12 实测，非迁移回归，属模块与飞书行为的交互效应）**：基线对照轮（`Config.antiread=false` 仅记录模式，app 已读行为原生）显示**原生 8.1.12 的回复时已读推送是每条回复都发的**（`READ_REQ #29/#35/#36 ids:0->0 maxPos=66/72/73 sendWin=true 仅记录`，30 秒连续对话内 3 次）；而模块开启时载体仅在进入会话后的第一次发送出现（#9、#25 两次独立命中，之后窗口内零载体）。即模块清空浏览请求这一行为本身改变了飞书后续发送载体的条件（确切内部触发机制在请求层面无法进一步定位，属飞书读同步逻辑）。legacy 与迁移版机制逐字一致、模块侧五环链路两次端到端实测存活，**迁移回归排除**。8.1.12 上功能的实际形态：浏览保持未读；进会话后第一次回复→积压全部补发已读；停留会话连续回复→对侧看到回复但消息仍显示未读，退出重进再回复即恢复。若上游希望改善（如回复时模块主动构造带暂存 ids 的读请求作为载体），属新适配工作，不在本次迁移范围。
- 第一轮测试（01:00 前后）失败原因：阶段 7 机器侧 F03/F04 验证的 force-stop 与用户测试窗口重叠，进程内存态 `PENDING_READ` 暂存被清——测试干扰，非代码问题；已留档避免复审误解。

### 3.2 第二轮行为测试（2026-10-06 02:00~02:30）：保密模式/去水印/截图/审计/设置入口/ProfileCapture/下载镜像

采集方式：用户操作期间挂持续 logcat（本地 `build/phase7-device/behavior-capture.log`，523,776 行，**0 FATAL**），脱敏证据 [behavior-evidence.log](behavior-evidence.log)（37 行：PII/uid/频道 id/文件名/安装路径已抹除）。

- **去水印 / 强制截图 / AI 速览屏蔽**：用户目视 PASS（三者均为静默 hook：改参或短路，无触发日志属预期）。
- **下载解锁（动态链路）**：打开文件详情页时，FileDownloadUnlock 的 after 回调动态挂上 `FileDetailModuleDependency$e` 的 3 个审计上报方法（02:08:51）——阶段 4 清单标注的「3 处 after 回调内动态 hook」在真机首次实测触发。
- **下载镜像**：`[dl] 已另存到系统下载` ×1，公共 Download 出现副本。
- **设置入口**：「高级设置卡片已注入」×4，卡片可打开进模块设置页。
- **ProfileCapture**：`归档资料` ×8，多目标按 uid 去重合并、累计递增。
- **审计屏蔽**：总闸双保险在 8.1.12 实际类上挂载成功（`AuditEventStorage.writeData#0` + `AuditManager#auditSecurityEvent`）；`CopyActionAuditUtil(q)`、`y33.a` 两个 7.70 专有混淆名匹配 0 个方法（与偏差 2 的 `gc6.a` 同类基线版本漂移，总闸不受影响）。
- **防已读（延续观测）**：READ_REQ #38~#50 浏览读全部清空暂存，与 §3.1 结论一致。

**发现：保密模式「转发」未解锁（复制/下载已恢复）——非迁移回归**

- 现象（用户 A/B 实测）：无模块手机点转发立即弹「保密模式已开启，禁止复制转发消息」（第一道客户端预检门）；有模块手机同样操作能进转发页（第一道门被 `getSwitch`+`MessageRestrictedActionInterceptor` hook 放行），但选人后提示「**群主或管理员开启了保密模式，禁止复制和转发消息**」，转发不生效。
- 迁移回归排除：同机转发普通消息正常（发送路径健康）；`SendReqHook` 仅开回复窗口后 `chain.proceed()` 原参透传不碰请求；`migration.onActivityResult` 按 request code 过滤不命中转发流程；interceptor 注册循环与 legacy 逐行等价（阶段 4 对账 40/40）。
- 定性：第二道策略门在模块与 legacy 的 hook 面之外（该文案在 base.apk 资源中不存在，属 split APK，未继续深挖是客户端二级检查还是服务端 DLP）。复制/下载为本地操作故可恢复；转发在更深层被强制。8.1.12 实际形态：保密会话复制/下载恢复，转发保持拦截——与 legacy 行为等价。
- 处置：解锁第二道门属新 hook 点=新功能决策，不混入迁移 PR，留上游维护者。

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
- 归档文件：[lifecycle-f03-f04.log](lifecycle-f03-f04.log)（161 行）、[reload-gate.log](reload-gate.log)、[native-regression.log](native-regression.log)、[behavior-evidence.log](behavior-evidence.log)（37 行，行为测试火点证据）、[device-env.txt](device-env.txt)；`*.log` 按惯例 `git add -f`。

## 阶段出口对照（08「阶段出口」）

| 出口条款 | 状态 |
|---|---|
| 国内版目标功能通过 | ✓ 机器侧全部通过 + 行为矩阵两轮执行完毕（§3.1/§3.2）；两项已知现象（回复窗口载体局限、保密模式转发第二道门）均定性为与 legacy 等价的基线行为，非迁移回归 |
| 国际版 | 未安装，记录未测试 ✓ |
| 多进程无重复注册和崩溃 | ✓（3 次重启 + reload 拒绝 + 换代 + 两轮行为测试，全程零 FATAL、零 skip duplicate） |
| native loader 和配置桥正常 | ✓ |
| hot reload 安全拒绝逻辑正常 | ✓（真机真框架协商拒绝） |
| 所有失败项有复现步骤和日志摘要 | 无迁移回归失败项；两项已知现象有现象描述、A/B 对照与定性（§3.1/§3.2） |

## 偏差与说明

1. **F06 N/A**：无老框架环境（非失败）。
2. **`screenshot-noaudit install failed: NoSuchMethodError gc6.a#onActivityResumed`**：两进程各 1 条。`gc6.a` 为飞书 7.70 专有混淆名（源码注释明示「仅 7.70 定位」），8.1.12 下类不存在，try/catch 降级为日志——legacy 版本同版本 app 下同样失败，属基线既有行为，非迁移回归。
3. **7.70 专有混淆名在 8.1.12 匹配 0 的还有两处**：`CopyActionAuditUtil(q)`（复制审计细粒度拦截）与 `y33.a`（审计服务访问器），与偏差 2 同类基线版本漂移；审计屏蔽的总闸双保险（`writeData` + `auditSecurityEvent`，稳定类名）不受影响、正常挂载。
4. **保密模式转发未解锁**（§3.2 发现）：第二道策略门在模块与 legacy hook 面之外，行为等价；解锁属新功能决策留上游，不阻塞阶段出口。
5. **reload 触发方式**：采用同版本 `adb install -r` 走 LSPosed Auto hot reload 路径（无 root 注入、无管理器 UI 自动化）；未测试「LSPosed 管理器手动 reload」入口（如有），属等价触发面。
6. ClassLoader identity（205258361/207587393）为进程内 identityHashCode，多次重启间出现同值属 ART 分配确定性，非固定 ID。

## 下一步

- **阶段 7 已审计通过并正式关闭（见 §7 审计轮次）**。
- 阶段 8：上游 PR（[09-upstream-pr.md](../../09-upstream-pr.md)）——PR 描述需包含：测试矩阵、两项已知现象的定性、版本号暂不变更说明、已知限制，以及 §7 登记的 native 可写路径加载的 Android 未来版本兼容风险提示。

## 7. 审计轮次

### 第一轮：通过，阶段 7 正式关闭，允许进入阶段 8（2026-10-06）

审计方复核 `7b1583c..b8c759b`（工作区干净）：4 个提交共新增 7 个文件、397 行，变更全部位于 `docs/libxposed-api-102-migration/`，无业务代码、构建脚本、native 或资源代码改动；当前本地 APK 与记录一致（1537004 字节，SHA-256 `0bd9786b…`）。

已确认通过：

- LSPosed v2.2.1 真实识别并加载 API 102 modern 模块；
- `onModuleLoaded`、`onPackageLoaded`、`onPackageReady` 生命周期行为符合设计；
- 3 次重启均保持 2 个目标进程、39+37 hooks、无重复分发、无 FATAL；
- 主进程与 `:wschannel` 进程覆盖符合预期，配置桥分别绑定；
- native 双 so 解出、加载、sqlite hook、维护线程和账号目录隔离均有真机证据；
- hot reload 真机拒绝路径通过（主进程列出全部资源 / `:wschannel` 仅配置桥回调 / 框架确认 refused / 拒绝时 PID 不变无崩溃 / 换代干净加载无 hook 叠加）；
- 14 项行为矩阵均已执行，迁移相关功能没有发现回归；
- 日志证据已脱敏，归档材料未发现应提交的账号数据或聊天正文。

两项已知现象的审计判断：

1. **回复已读窗口载体局限**：`antiread=false` 原生对照证明飞书 8.1.12 原生每次回复都发读推送；模块开启后载体减少是清空浏览请求与飞书同步逻辑的交互结果，不是 API 102 迁移引入的回归。回复窗口五环链路（拦截→暂存→开窗→合并→放行）端到端验证通过。
2. **保密模式转发未完全解锁**：复制和下载恢复，转发在选人后的第二道策略门被拦截。现有证据足以证明第一层模块 hook 已生效、普通消息转发链路正常、`SendReqHook` 只开窗透传原参、`onActivityResult` 不误拦转发、第二道门不属于本次迁移覆盖的 hook 面——**记录为既有行为/功能边界，不是迁移回归，也不应在本次迁移 PR 中顺手扩展为新 hook 功能**。

非阻塞风险提示（审计方提出，登记在案）：native 回归日志出现 `Attempt to load writable file … This will throw on a future Android version`（见 [native-regression.log](native-regression.log)，两个 so 均从 `/data/data/.../` 可写路径加载）。当前 Android 17 / SDK 37 实测仍加载成功，且 native 路径属于本次明确冻结的既有行为，**不阻塞阶段 7**；建议在阶段 8 PR 说明或后续独立议题中登记未来 Android 版本兼容风险。

阶段边界确认（均正确记录，不构成阶段 7 阻塞）：国际版未安装故未测试；F06 老框架设备 N/A；hot reload allow 路径真机不可达由阶段 5 的 49/49 JVM 测试覆盖；LSPosed 管理器手动 reload 入口未单独测试；Linux 实机构建仍待上游 CI。

**最终判断：阶段 7 通过并正式关闭，可以进入阶段 8 上游 PR。**
