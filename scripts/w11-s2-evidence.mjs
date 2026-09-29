// W11 运行期取证脚本（S2 形态：先发图识图，后追问不重发图片）
// 用法：node scripts/w11-s2-evidence.mjs [baseUrl]
// 三步：
//   ① openid_s2_a 发图片消息（含 PicUrl）→ 应触发 recognize_image 并写识图缓存
//   ② openid_s2_a 追问「它大概多大了」→ ask_image 应可见并被调用（缓存续接）
//   ③ openid_s2_b（无缓存）问同一句 → ask_image 不应在下发集（负证）
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
    return `<xml><ToUserName><![CDATA[gh_w11]]></ToUserName>` +
        `<FromUserName><![CDATA[${openid}]]></FromUserName>` +
        `<CreateTime>${Math.floor(Date.now() / 1000)}</CreateTime>` +
        inner + `<MsgId>${msgId}</MsgId></xml>`;
}

const imageMsg = (openid, msgId, picUrl) => xml(openid, msgId,
    `<MsgType><![CDATA[image]]></MsgType><PicUrl><![CDATA[${picUrl}]]></PicUrl>` +
    `<MediaId><![CDATA[media-1]]></MediaId>`);

const textMsg = (openid, msgId, content) => xml(openid, msgId,
    `<MsgType><![CDATA[text]]></MsgType><Content><![CDATA[${content}]]></Content>`);

async function post(label, body) {
    const res = await fetch(`${base}/api/wx/callback?${query()}`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/xml' },
        body,
    });
    console.log(`[POST] ${label} -> HTTP ${res.status} body=${await res.text()}`);
}

await post('① openid_s2_a 发图片', imageMsg('openid_s2_a', 910001, 'http://example.com/cat.jpg'));
await sleep(2500);
await post('② openid_s2_a 追问（有缓存）', textMsg('openid_s2_a', 910002, '它大概多大了'));
await sleep(2500);
await post('③ openid_s2_b 同问（无缓存，负证）', textMsg('openid_s2_b', 910003, '它大概多大了'));
await sleep(2500);
console.log('done');
