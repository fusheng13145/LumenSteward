// W7/W8 最小提醒切片运行期取证脚本（P4 承担者：承诺 → 到点投递）
// 用法：node scripts/w7-w8-reminder-evidence.mjs [baseUrl]
// 前置（脚本内完成）：登录 → PUT reminder.max-per-48h=2（预算取证口径）→ PUT memory.growth.enabled=true
// 步骤：
//   ① 发「记得提醒我 2 分钟后给咪咪打疫苗」→ 生长管道抽取 COMMITMENT → biz_commitment 登记（due≈+2min）
//   ② 再发两条同型提醒 → 窗口内第 3 条应因预算（max=2）留 PENDING
//   ③ 等 2.5 分钟 → 两条 PENDING 到期投递（第 1、2 条），第 3 条仍 PENDING（预算外，等窗口）
// 侧证（脚本外）：SELECT * FROM biz_commitment；MockWechatTransport 出站日志（clawbot 线程）；
//   负证：growth 关闭时不登记（手册 §7.21）。
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

async function api(method, path, jwt, body) {
    const res = await fetch(`${base}${path}`, {
        method,
        headers: {
            'Content-Type': 'application/json',
            ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
        },
        body: body == null ? undefined : JSON.stringify(body),
    });
    return { status: res.status, text: await res.text() };
}

const textMsg = (openid, content) => {
    const ts = Math.floor(Date.now() / 1000);
    const msgId = 960000000 + Math.floor(Math.random() * 99999999);
    return `<xml><ToUserName><![CDATA[gh_w78]]></ToUserName>` +
        `<FromUserName><![CDATA[${openid}]]></FromUserName><CreateTime>${ts}</CreateTime>` +
        `<MsgType><![CDATA[text]]></MsgType><Content><![CDATA[${content}]]></Content>` +
        `<MsgId>${msgId}</MsgId></xml>`;
};

async function callback(label, xml) {
    const res = await fetch(`${base}/api/wx/callback?${query()}`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/xml' },
        body: xml,
    });
    console.log(`[${label}] HTTP ${res.status}`);
}

const login = await api('POST', '/api/auth/login', null,
    { username: 'superadmin', password: 'Admin@123456' });
const jwt = JSON.parse(login.text).data.token;
console.log(`[登录] HTTP ${login.status}`);

// 预算取证口径：窗口内最多 2 条
const budget = await api('PUT', '/api/configs', jwt,
    { reason: 'w7w8 取证：预算收紧到 2', items: [{ configKey: 'reminder.max-per-48h', configValue: '2' }] });
console.log(`[预算热改] HTTP ${budget.status}（期待 200）`);
const growth = await api('PUT', '/api/configs', jwt,
    { reason: 'w7w8 取证：开启生长', items: [{ configKey: 'memory.growth.enabled', configValue: 'true' }] });
console.log(`[开启生长] HTTP ${growth.status}`);

const openid = 'e2e_w78_rem';
await callback('① 托付提醒（+PT2M）', textMsg(openid, '记得提醒我 2 分钟后给咪咪打疫苗'));
await sleep(6000);
await callback('② 托付提醒（+PT3M）', textMsg(openid, '记得提醒我 3 分钟后给花浇水'));
await sleep(6000);
await callback('③ 托付提醒（+PT4M，应因预算留 PENDING）', textMsg(openid, '记得提醒我 4 分钟后取快递'));
await sleep(8000);
console.log('登记阶段完成（脚本退出；等约 2.5~4 分钟后用 SQL 与日志核投递）');
console.log('侧证 SQL: SELECT id, openid, content, due_at, status, delivered_at FROM biz_commitment WHERE openid LIKE \'e2e_w78%\';');
console.log('侧证日志: grep "到期提醒" 后端日志（clawbot 线程的 Mock 出站客服消息）');
