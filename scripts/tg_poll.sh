#!/usr/bin/env bash
# 拉取 fucklark 群里 @fucklark_bot 的新消息(含回复机器人消息), 输出 TSV 供值守自动化处理:
#   update_id \t message_id \t user_id \t username \t 显示名 \t 文本(换行替换为 ⏎)
# 偏移量持久化在 ~/.fucklark_tg_offset, 取回后立即前移(至多一次语义)。
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
    if (m.get('from') or {}).get('id') == BOTID:
        continue
    if str(m.get('chat', {}).get('id')) != CHAT:
        continue
    txt = m.get('text') or m.get('caption') or ''
    ents = m.get('entities') or []
    mentioned = any(e.get('type') == 'mention' and '@fucklark_bot' == txt[e['offset']:e['offset'] + e['length']] for e in ents)
    rep = m.get('reply_to_message') or {}
    botreply = (rep.get('from') or {}).get('id') == BOTID
    if not (mentioned or botreply):
        continue
    f = m.get('from') or {}
    un = f.get('username') or ''
    name = (f.get('first_name') or '') + ((' ' + f['last_name']) if f.get('last_name') else '')
    t = txt.replace('\t', ' ').replace('\n', ' ⏎ ')
    out.append(f"{uid}\t{m['message_id']}\t{f.get('id')}\t{un}\t{name}\t{t}")
print('\n'.join(out))
if mx > 0:
    open(os.path.expanduser('~/.fucklark_tg_offset'), 'w').write(str(mx))
PY
