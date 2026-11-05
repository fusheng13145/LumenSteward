// W13 / W15 / W16 运行期取证脚本（三批广度项：音色目录 / 长任务通道 / 链接读取）
// 用法：node scripts/w13-w15-w16-evidence.mjs [baseUrl] [--restart-probe]
//   默认执行：登录 → W13 配置面（新键可见 + 非法值 400 + 合法热改复原）→ W16 链接消息
//   → W15 正常提交/查询（3s 任务）。
//   --restart-probe：只提交一个 delay 25s 的任务并打印 id（供重启续跑取证编排使用）。
import crypto from 'node:crypto';

const base = process.argv[2] ?? 'http://localhost:8094';
const restartProbe = process.argv.includes('--restart-probe');
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
    const msgId = 940000000 + Math.floor(Math.random() * 99999999);
    return { ts, msgId, body: `<xml><ToUserName><![CDATA[gh_w131516]]></ToUserName>` +
        `<FromUserName><![CDATA[${openid}]]></FromUserName><CreateTime>${ts}</CreateTime>` +
        `<MsgType><![CDATA[text]]></MsgType><Content><![CDATA[${content}]]></Content>` +
        `<MsgId>${msgId}</MsgId></xml>` };
};

const linkMsg = (openid, title, url) => {
    const ts = Math.floor(Date.now() / 1000);
    const msgId = 950000000 + Math.floor(Math.random() * 99999999);
    return `<xml><ToUserName><![CDATA[gh_w131516]]></ToUserName>` +
        `<FromUserName><![CDATA[${openid}]]></FromUserName><CreateTime>${ts}</CreateTime>` +
        `<MsgType><![CDATA[link]]></MsgType><Title><![CDATA[${title}]]></Title>` +
        `<Description><![CDATA[分享摘要]]></Description><Url><![CDATA[${url}]]></Url>` +
        `<MsgId>${msgId}</MsgId></xml>`;
};

async function callback(label, xml) {
    const res = await fetch(`${base}/api/wx/callback?${query()}`, {
        method: 'POST',
        headers: { 'Content-Type': 'text/xml' },
        body: xml,
    });
    console.log(`[${label}] HTTP ${res.status} body=${(await res.text()).slice(0, 90)}`);
}

// ===== 登录 =====
const login = await api('POST', '/api/auth/login', null,
    { username: 'superadmin', password: 'Admin@123456' });
const jwt = JSON.parse(login.text).data.token;
console.log(`[登录] HTTP ${login.status} role=${JSON.parse(login.text).data.role}`);

if (restartProbe) {
    // 重启续跑取证素材：25s 任务（在 ~5s 时被杀，重启后由续跑逻辑恢复）
    const submit = await api('POST', '/api/long-tasks', jwt,
        { type: 'sample_report', openid: 'e2e_w15_resume', payload: '{"title":"续跑取证","delay_seconds":25}' });
    console.log(`[W15 续跑素材] HTTP ${submit.status} body=${submit.text}`);
    console.log('done (restart-probe)');
    process.exit(0);
}

// ===== W13：配置面 =====
const configs = await api('GET', '/api/configs', jwt, null);
const configList = JSON.parse(configs.text).data?.items ?? JSON.parse(configs.text).data ?? [];
const wanted = ['tts.voice-catalog', 'tts.voice-default', 'orchestration.persona', 'longtask.pool-size', 'longtask.timeout-seconds'];
for (const key of wanted) {
    const found = (JSON.stringify(configList) || '').includes(key);
    console.log(`[W13/W15 配置可见] ${key} -> ${found ? 'FOUND' : 'MISSING'}`);
}
const badCatalog = await api('PUT', '/api/configs', jwt,
    { reason: 'w13 取证：非法目录', items: [{ configKey: 'tts.voice-catalog', configValue: '不是JSON' }] });
console.log(`[W13 非法目录] HTTP ${badCatalog.status}（期待 400）${badCatalog.text.slice(0, 110)}`);
const longPersona = await api('PUT', '/api/configs', jwt,
    { reason: 'w13 取证：超长人设', items: [{ configKey: 'orchestration.persona', configValue: '你'.repeat(501) }] });
console.log(`[W13 超长人设] HTTP ${longPersona.status}（期待 400）${longPersona.text.slice(0, 110)}`);
const hotCatalog = await api('PUT', '/api/configs', jwt,
    { reason: 'w13 取证：热改目录', items: [{ configKey: 'tts.voice-catalog', configValue: '["default","gentle","calm","lively","story","news","xiaochen"]' }] });
console.log(`[W13 合法热改] HTTP ${hotCatalog.status}（期待 200）`);
const restore = await api('PUT', '/api/configs', jwt,
    { reason: 'w13 取证：复原默认目录', items: [{ configKey: 'tts.voice-catalog', configValue: '["default","gentle","calm","lively","story","news"]' }] });
console.log(`[W13 复原] HTTP ${restore.status}`);

// ===== W13：语音链路（念出来 → synthesize_voice，W12 清洗运行期样本） =====
const voice = textMsg('e2e_w13_voice', '把这句话念出来');
await callback('W13 语音消息', voice.body);
await sleep(2500);

// ===== W16：链接消息 → read_link =====
const link = linkMsg('e2e_w16_link', '示例文章', 'https://example.com/hello');
await callback('W16 链接消息', link);
await sleep(2500);

// ===== W15：提交 3 秒样例任务并轮询 =====
const submit = await api('POST', '/api/long-tasks', jwt,
    { type: 'sample_report', openid: 'e2e_w15_basic', payload: '{"title":"取证报告","delay_seconds":3}' });
console.log(`[W15 提交] HTTP ${submit.status} body=${submit.text}`);
const taskId = JSON.parse(submit.text).data.id;
for (let i = 0; i < 15; i++) {
    await sleep(1000);
    const poll = await api('GET', `/api/long-tasks/${taskId}`, jwt, null);
    const vo = JSON.parse(poll.text).data;
    if (vo.status === 'SUCCEEDED' || vo.status === 'FAILED') {
        console.log(`[W15 终态] id=${taskId} status=${vo.status} delivered=${vo.delivered} result=${(vo.resultJson ?? '').slice(0, 90)}`);
        break;
    }
}
console.log('done');
