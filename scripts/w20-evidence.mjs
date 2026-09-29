// W20 首发体验运行期取证脚本
// 用法：node scripts/w20-evidence.mjs [baseUrl]
// 五步：
//   ① 关注（subscribe）→ 欢迎语 = 可配正文（若已热改则呈现新文案）+ 实际下发集能力自述
//   ② 发「帮助」→ 确定性能力自述（不调 LLM，与实际下发集一致）
//   ③ 菜单 CLICK EventKey=帮助 → 能力自述（菜单按钮有真实响应）
//   ④ 菜单 CLICK EventKey=记一下咪咪的疫苗 → 路由进对话引擎（Mock 默认回复）
//   ⑤ 菜单 view EventKey=URL → 确认回复
import crypto from 'node:crypto';

const base = process.argv[2] ?? 'http://localhost:8094';
const token = 'clawbot-local-token';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function query() {
    const ts = Math.floor(Date.now() / 1000);
    const nonce = 'n' + crypto.randomBytes(6).toString('hex');
    const signature = crypto.createHash('sha1')
        .update([token, ts, nonce].sort().join('')).digest('hex');
    return `signature=${signature}&timestamp=${ts}&nonce=${nonce}`;
}

function xml(openid, msgId, inner) {
    return `<xml><ToUserName><![CDATA[gh_w20]]></ToUserName>` +
        `<FromUserName><![CDATA[${openid}]]></FromUserName>` +
        `<CreateTime>${Math.floor(Date.now() / 1000)}</CreateTime>` +
        inner + `</xml>`;
}

const eventMsg = (openid, event, eventKey) => xml(openid, `${event}-no-msgid`,
    `<MsgType><![CDATA[event]]></MsgType><Event><![CDATA[${event}]]></Event>` +
    (eventKey ? `<EventKey><![CDATA[${eventKey}]]></EventKey>` : ``));

const textMsg = (openid, content) => xml(openid, `w20-${Math.random().toString(36).slice(2, 8)}`,
    `<MsgType><![CDATA[text]]></MsgType><Content><![CDATA[${content}]]></Content><MsgId>${Date.now()}</MsgId>`);

async function post(label, body) {
    const res = await fetch(`${base}/api/wx/callback?${query()}`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/xml' },
        body,
    });
    const text = await res.text();
    const reply = /<Content><!\[CDATA\[([\s\S]*?)\]\]><\/Content>/.exec(text)?.[1] ?? text;
    console.log(`[${label}] HTTP ${res.status}\n  ↳ ${reply.slice(0, 200)}\n`);
}

await post('① subscribe', eventMsg('openid_w20_user', 'subscribe', null));
await sleep(800);
await post('② text 帮助', textMsg('openid_w20_user', '帮助'));
await sleep(800);
await post('③ CLICK 帮助', eventMsg('openid_w20_user', 'CLICK', '帮助'));
await sleep(800);
await post('④ CLICK 记一下咪咪的疫苗', eventMsg('openid_w20_user', 'CLICK', '记一下咪咪的疫苗'));
await sleep(1200);
await post('⑤ view', eventMsg('openid_w20_user', 'view', 'https://example.com/menu'));
await sleep(800);
console.log('done');
