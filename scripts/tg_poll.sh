#!/usr/bin/env bash
# 拉取 fucklark 群的全部新消息(不过滤是否@机器人, 由值守自动化自行判断相关性), 输出 TSV:
#   update_id \t message_id \t thread_id \t reply_to \t user_id \t username \t 显示名 \t 标记 \t 文本(换行替换为 ⏎)
#   thread_id: 话题群的话题ID(无则0), 回复该消息时 sendMessage 必须带上
#   reply_to: 所回复的消息ID(无则0)
#   标记: MENTION=提及@fucklark_bot, REPLY_BOT=回复机器人消息, 可叠加, 空=普通群聊
# 偏移量持久化在 ~/.fucklark_tg_offset, 取回后立即前移(至多一次语义); 机器人消息已排除。
set -u
TOK="$(cat "$HOME/.fucklark_tg_token")"
PROXY="${TG_PROXY:-socks5h://127.0.0.1:7897}"
OFF_FILE="$HOME/.fucklark_tg_offset"
OFF=$(cat "$OFF_FILE" 2>/dev/null || echo 0)
CURL="curl -sS --ssl-no-revoke -x $PROXY --connect-timeout 20 --max-time 60"
for i in 1 2 3 4 5; do R=$($CURL "https://api.telegram.org/bot$TOK/getUpdates?offset=$((OFF+1))&timeout=0&allowed_updates=%5B%22message%22%5D" 2>/dev/null) && [ -n "$R" ] && break; sleep $((i*2)); done
[ -n "${R:-}" ] || { echo "POLL_FAILED" >&2; exit 2; }
printf '%s' "$R" > "$HOME/.fucklark_tg_updates.json"
python - <<'PY'
import json, os
d = json.load(open(os.path.expanduser('~/.fucklark_tg_updates.json'), encoding='utf-8'))
ups = d.get('result', [])
mx = 0
CHAT = '-1004312365887'
BOTID = 8852854621
out = []
for u in ups:
    uid = u.get('update_id', 0)
    mx = max(mx, uid)
    m = u.get('message')
    if not m:
        continue
    if str(m.get('chat', {}).get('id')) != CHAT:
        continue
    f = m.get('from') or {}
    if f.get('is_bot'):
        continue
    txt = m.get('text') or m.get('caption') or ''
    ents = m.get('entities') or []
    mentioned = any(e.get('type') == 'mention' and '@fucklark_bot' == txt[e['offset']:e['offset'] + e['length']] for e in ents)
    rep = m.get('reply_to_message') or {}
    botreply = (rep.get('from') or {}).get('id') == BOTID
    flags = []
    if mentioned:
        flags.append('MENTION')
    if botreply:
        flags.append('REPLY_BOT')
    tid = m.get('message_thread_id') or 0
    rto = rep.get('message_id', 0)
    un = f.get('username') or ''
    name = (f.get('first_name') or '') + ((' ' + f['last_name']) if f.get('last_name') else '')
    t = txt.replace('\t', ' ').replace('\n', ' ⏎ ')
    out.append(f"{uid}\t{m['message_id']}\t{tid}\t{rto}\t{f.get('id')}\t{un}\t{name}\t{'+'.join(flags)}\t{t}")
print('\n'.join(out))
if mx > 0:
    open(os.path.expanduser('~/.fucklark_tg_offset'), 'w').write(str(mx))
PY
