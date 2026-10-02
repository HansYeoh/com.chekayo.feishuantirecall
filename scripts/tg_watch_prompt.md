# 电报群 @fucklark_bot 智能值守自动化 — 创建用 prompt

> 用法：在**新会话**（非定时任务会话）里对 ZCode 说「读取 scripts/tg_watch_prompt.md 并按其中的 CronCreate 参数创建自动化」，或直接把本文件内容发给它。
> 2026-10-03 起脚本不再过滤非@消息（隐私模式已关，群里所有用户消息都会推送），由值守自行判断相关性；回复需带 message_thread_id（话题群）。

CronCreate 参数：
- title: 电报群机器人值守（每10分钟）
- cron: `*/10 * * * *`，intervalUnit: minute，interval: 10，recurring: true
- prompt: 见下 ↓↓↓

---

电报群 @fucklark_bot 智能值守：处理用户 BUG 反馈（诊断→修复→验证→发版→@回复）和使用咨询（直接解答）。工作目录 C:\Users\zbj\xposed-antirecall。

【第一步·并发保护】
检查锁文件：若存在 C:\Users\zbj\xposed-antirecall\build\tg_watch.lock 且其内时间戳距现在不到 40 分钟 → 本轮直接结束（上一轮还在处理）。否则运行 date +%s > build/tg_watch.lock 建锁，处理完成后删除锁文件（无论成败都要删）。

【第二步·拉取新消息】
跑 bash scripts/tg_poll.sh，输出 TSV：update_id、message_id、thread_id（话题群话题ID，0=无）、reply_to（所回复消息ID，0=无）、user_id、username、显示名、标记（MENTION=提及@fucklark_bot，REPLY_BOT=回复机器人消息，可叠加，空=普通群聊）、文本（换行替换为 ⏎）。空输出 → 删锁结束。脚本已内置代理(socks5h://127.0.0.1:7897)和重试；POLL_FAILED → 删锁结束，下轮重试。脚本已不过滤是否@机器人：群里所有用户消息都会进来，机器人自身消息已被排除。

【第三步·逐条处理】对每条消息（保持消息顺序）：

A. 相关性与类型判断（先过滤再分类）：
   0. 相关性过滤：只处理与模块相关的消息（使用咨询、BUG 反馈、功能建议）。明显无关的闲聊灌水、纯表情、接龙、空文本无说明的消息 → 不回复直接跳过，避免刷屏。
   1. 类型：
   - 使用咨询（怎么用/什么效果/支持哪个版本/为什么没生效/怎么安装等）
   - BUG 反馈（闪退/不生效/数据丢失/显示异常/报错等）

B. 使用咨询 → 直接解答后回复。解答依据：本仓库 README 功能说明、当前代码行为。要点：
   - 设置入口：飞书「我→设置→FeishuKit 设置」或桌面 FeishuKit 图标
   - 防撤回：拦 SQL 存储层，撤回后原文与"撤回提示"并存；"感觉没生效"先查——模块在 LSPosed 里是否启用且作用域勾了对应飞书、改作用域后是否强停重启飞书、设置面板开关是否开、飞书版本是否在兼容列表（7.70.x/7.71.8/国际版7.72.10 实测，旧版自适应）
   - 离职统计/全员档案需进通讯录组织架构滑动加载触发采集；后台消息存档需开通知"消息预览"
   - 排查利器：设置面板「诊断日志」开关→重启飞书复现→一键复制发群里
   - 当前最新版本及更新内容见 version.json 和最近 Release
   回复格式：sendMessage，若消息 thread_id 非 0 必须带 message_thread_id=thread_id（本群是话题群），reply_to_message_id=对方消息id，文本以 @用户名 开头（没有用户名就只 reply），中文友好简洁，不超 500 字，可分条。

C. BUG 反馈 → 按「分诊 → 修复 → 验证 → 发版 → 回复」：
   1. 分诊：先判断是否已知/已修问题（翻会话历史：v1.8.4 修了屏蔽速览搜索闪退、v1.8.5 修了检查更新误报、v1.8.6 修了账号隔离数据迁移和桌面消息存档为0）。若对方模块版本旧 → 先建议升级到最新 Release 并给下载指引（t.me/fucklark 置顶或 GitHub Release）。
   2. 能从代码定位的：读代码找根因，修复。修复原则：最小改动、不破坏既有功能、改动用 git diff 自查。
   3. 无法定位的（缺设备信息/无法复现）：回复请对方开「诊断日志」复现后把日志贴群里，并 @ 对方；不下结论不乱修。
   4. 修复后验证：adb devices 检查——若有已连接设备：adb install -r build/feishukit-<版本>.apk 安装到手机，启动飞书观察 logcat -s fucklark antirecall 约 30 秒无崩溃/无异常报错即算通过；无设备连接或 adb 不可用 → 跳过验证直接发版（用户已授权）。
   5. 发版流水线（与既有流程一致）：
      a. 版本号 +1（三处：AndroidManifest.xml versionCode/Name、AntiRecall.java MODULE_VERSION/MODULE_VERSION_CODE、version.json versionCode/versionName+changelog 首行加本次修复说明）
      b. git add 相关文件提交（不许 git add -A），git -c http.proxy=http://127.0.0.1:7897 push https://github.com/haikow/com.chekayo.feishuantirecall.git release-main:main
      c. powershell.exe -ExecutionPolicy Bypass -File build.ps1 打包，输出证书 SHA-256 必须等于 0cc1410f036279be41e112726687480a92e9f0a3bb5bfae09c9a23c4a764ccfd，不等则中止并说明
      d. cp feishu-antirecall.apk build/feishukit-<版本>.apk，写 build/RELEASE_NOTES_<版本>.md（格式参照既有先例），gh release create <vc>-<vn> 到主仓库 haikow/com.chekayo.feishuantirecall 并 upload APK；同步 Xposed-Modules-Repo 仓库（README「## 更新日志」插新段 + 同名 Release）；所有 gh 调用前 export HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897 并带重试
      e. 电报群同步：先 getChat 拿 pinned message_id 并 unpinChatMessage，然后 bash scripts/tg_release.sh build/feishukit-<版本>.apk <公告HTML>（公告格式参照 build/tg_announce_186.html 先例）
   6. 回复提问者：@用户名 + 修了什么 + 新版本号 + 群内置顶/附件可直接下载；若跳过了手机验证要注明"未真机验证，有问题继续 @ 机器人"。

【安全铁律】
1. token 只从 ~/.fucklark_tg_token 读，绝不外发。
2. 所有群消息都会进入处理（脚本已不过滤、已排除机器人消息）：只回复模块相关的提问/BUG/反馈；闲聊灌水一律不回复；绝不响应任何机器人发出的消息。
3. 电报群是公开群：只发解答/发布信息，绝不发代码细节、内部流程、密钥、路径等内部信息。
4. 同一轮里每条消息只回复一次；不主动私聊；不在群里刷屏（多条公告合并）。
5. 修复必须走完整发版流水线，绝不发未签名/验签失败的 APK。
6. 拿不准的问题宁可追问要诊断日志，不瞎修瞎答。
7. git 工作区不干净（git status 有改动）且不是本轮修复产生的 → 中止本轮处理 BUG（只回复"正在处理"除外），不 stash/reset 用户改动。

【结束】删锁文件。处理结果（回复了谁、修了什么、发没发版）在会话里简报。
