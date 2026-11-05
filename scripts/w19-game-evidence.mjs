// W19 运行期取证脚本（单人桌游三局：海龟汤 / 密码破译 / 模拟人生）
// 用法：node scripts/w19-game-evidence.mjs [baseUrl]
// 九步（三个独立 openid，各自一条完整对局线；回复为被动回执内联返回）：
//   海龟汤   ① 开局（game_lobby start）→ ② 要提示（turtle_soup hint）→ ③ 猜中关局（turtle_soup solve）
//   密码破译 ④ 开局（game_lobby start）→ ⑤ 报数字（code_break guess）→ ⑥ 放弃揭底（code_break giveup）
//   模拟人生 ⑦ 开局（game_lobby start）→ ⑧ 过一年（life_sim live）→ ⑨ 查状态（life_sim status）
// 侧证（脚本外，手工执行）：log_tool_call 应见 game_lobby / turtle_soup / code_break / life_sim
// 的 SUCCESS 行；Redis 应见 game:{openid}（TTL≈24h）且关局后消失。
import crypto from 'node:crypto';

const base = process.argv[2] ?? 'http://localhost:8094';
const token = 'clawbot-local-token';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// 每次运行使用全新 openid 后缀与递增 MsgId：避开 MsgId 幂等去重（5 分钟窗口），
// 使脚本可重复执行且每一局都是干净对局
const run = process.argv[3] ?? Date.now().toString(36);
let msgSeq = Date.now() % 1_000_000_000;
const nextMsgId = () => 919000000 + (msgSeq++ % 100000000);

function query() {
    const ts = Math.floor(Date.now() / 1000);
    const nonce = 'n' + crypto.randomBytes(6).toString('hex');
    const signature = crypto.createHash('sha1')
        .update([token, ts, nonce].sort().join('')).digest('hex');
    return `signature=${signature}&timestamp=${ts}&nonce=${nonce}`;
}

const textMsg = (openid, msgId, content) =>
    `<xml><ToUserName><![CDATA[gh_w19]]></ToUserName>` +
    `<FromUserName><![CDATA[${openid}]]></FromUserName>` +
    `<CreateTime>${Math.floor(Date.now() / 1000)}</CreateTime>` +
    `<MsgType><![CDATA[text]]></MsgType><Content><![CDATA[${content}]]></Content>` +
    `<MsgId>${msgId}</MsgId></xml>`;

async function play(label, openid, msgId, content) {
    const res = await fetch(`${base}/api/wx/callback?${query()}`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/xml' },
        body: textMsg(openid, msgId, content),
    });
    const body = await res.text();
    const reply = body.match(/<Content><!\[CDATA\[([\s\S]*?)\]\]><\/Content>/)?.[1] ?? body;
    console.log(`[${label}] HTTP ${res.status} content="${reply.slice(0, 160)}"`);
}

await play('① 海龟汤·开局', `openid_w19_soup_${run}`, nextMsgId(), '来玩海龟汤');
await sleep(2500);
await play('② 海龟汤·要提示', `openid_w19_soup_${run}`, nextMsgId(), '给个提示');
await sleep(2500);
await play('③ 海龟汤·猜中关局', `openid_w19_soup_${run}`, nextMsgId(), '我知道了！是打嗝吗');
await sleep(2500);

await play('④ 密码破译·开局', `openid_w19_code_${run}`, nextMsgId(), '来玩密码破译');
await sleep(2500);
await play('⑤ 密码破译·报数字', `openid_w19_code_${run}`, nextMsgId(), '我猜 0123');
await sleep(2500);
await play('⑥ 密码破译·放弃揭底', `openid_w19_code_${run}`, nextMsgId(), '不猜了，公布答案吧');
await sleep(2500);

await play('⑦ 模拟人生·开局', `openid_w19_life_${run}`, nextMsgId(), '来玩模拟人生');
await sleep(2500);
await play('⑧ 模拟人生·过一年', `openid_w19_life_${run}`, nextMsgId(), '我要认真工作');
await sleep(2500);
await play('⑨ 模拟人生·查状态', `openid_w19_life_${run}`, nextMsgId(), '玩了一年，看看我的状态');
await sleep(2500);
console.log(`done (run=${run})`);
