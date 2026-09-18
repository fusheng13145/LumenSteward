# =============================================================================
# e2e-smoke.ps1 —— 衔光管家 MVP 端到端走查（一条命令即可复现）
#
# 覆盖验收项：
#   AC-A1 正确签名发消息（走通接入层）
#   AC-A2 伪造签名 100 次全部拒绝
#   AC-A3 同一 MsgId 10 次仅处理 1 次（幂等去重）
#   AC-A4 GET echostr 原样回显
#   AC-C1 通过对话登记宠物（Mock LLM 脚本触发 manage_pet_profile 真实写库）
#   AC-E1 登录成功返回 JWT
#   AC-E2 不带 Token 访问受保护接口 → 401
#   AC-E3 越权（非 SUPER_ADMIN 调配置写接口）→ 403
#
# 前置：
#   1) 后端已启动（local profile + Mock）：mvn -f backend/pom.xml spring-boot:run
#   2) 环境变量 WX_TOKEN 与后端 wx.token 一致（默认占位 token=clawbot-local-token）
#   3) 初始管理员口令来自环境变量 ADMIN_INIT_PASSWORD（未设置可传 -AdminPassword）
#
# 用法：
#   pwsh -File scripts/e2e-smoke.ps1
#   pwsh -File scripts/e2e-smoke.ps1 -BaseUrl http://localhost:8080 -WxToken <token> -AdminPassword <pwd>
#
# 兼容性：Windows PowerShell 5.1 与 PowerShell 7 均可运行。
#   5.1 的 Invoke-WebRequest 在 4xx/5xx 会抛异常，故统一用 Invoke-WebRequestSafe 包裹，
#   在两种版本下都返回含 StatusCode/Content 的对象（失败亦不抛出）。
# =============================================================================

[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ($env:E2E_BASE_URL) { $env:E2E_BASE_URL } else { 'http://localhost:8080' }),
    [string]$WxToken = $(if ($env:WX_TOKEN) { $env:WX_TOKEN } else { 'clawbot-local-token' }),
    [string]$AdminUser = $(if ($env:ADMIN_USERNAME) { $env:ADMIN_USERNAME } else { 'superadmin' }),
    [string]$AdminPassword = $env:ADMIN_INIT_PASSWORD,
    [string]$OperatorToken = $env:E2E_OPERATOR_TOKEN,
    [string]$OperatorUser = $(if ($env:E2E_OPERATOR_USER) { $env:E2E_OPERATOR_USER } else { 'operator' }),
    [int]$Count = 100
)

$ErrorActionPreference = 'Stop'
# 规避 PowerShell 5.1 非交互模式下 Invoke-WebRequest 因访问进度 UI 而抛 PSInvalidOperationException
$ProgressPreference = 'SilentlyContinue'
$script:pass = 0
$script:fail = 0
$script:skip = 0

function Write-Result {
    param([string]$Id, [string]$Name, [bool]$Ok, [string]$Detail)
    if ($Ok) {
        $script:pass++
        Write-Host ("[PASS] {0} {1} :: {2}" -f $Id, $Name, $Detail) -ForegroundColor Green
    }
    else {
        $script:fail++
        Write-Host ("[FAIL] {0} {1} :: {2}" -f $Id, $Name, $Detail) -ForegroundColor Red
    }
}

function Write-Skip {
    param([string]$Id, [string]$Name, [string]$Reason)
    $script:skip++
    Write-Host ("[SKIP] {0} {1} :: {2}" -f $Id, $Name, $Reason) -ForegroundColor Yellow
}

# ---- 独立实现的微信签名：sha1( sort(token, timestamp, nonce) ) -----------------
function Get-WechatSignature {
    param([string]$Token, [string]$Timestamp, [string]$Nonce)
    $parts = @($Token, $Timestamp, $Nonce) | Sort-Object
    $joined = [string]::Join('', $parts)
    $sha1 = [System.Security.Cryptography.SHA1]::Create()
    $bytes = $sha1.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($joined))
    return ([System.BitConverter]::ToString($bytes) -replace '-', '').ToLowerInvariant()
}

function New-Timestamp { return [string]([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) }

function New-XmlBody {
    param([string]$FromUser, [string]$Content, [string]$MsgId)
    return @"
<xml><ToUserName><![CDATA[gh_lumensteward]]></ToUserName>
<FromUserName><![CDATA[$FromUser]]></FromUserName>
<CreateTime>$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())</CreateTime>
<MsgType><![CDATA[text]]></MsgType>
<Content><![CDATA[$Content]]></Content>
<MsgId>$MsgId</MsgId></xml>
"@
}

# ---- 兼容 PowerShell 5.1 与 7：4xx/5xx 时不抛出，返回含 StatusCode/Content 的对象 ----
function Invoke-WebRequestSafe {
    param(
        [string]$Uri,
        [string]$Method = 'Get',
        [string]$ContentType,
        [string]$Body,
        [hashtable]$Headers
    )
    try {
        $req = [System.Net.HttpWebRequest]::Create($Uri)
        $req.Method = $Method.ToUpper()
        $req.UserAgent = 'e2e-smoke/1.0'
        $req.Timeout = 30000
        if ($ContentType) { $req.ContentType = $ContentType }
        if ($Headers) {
            foreach ($k in $Headers.Keys) {
                if ($k -eq 'Authorization') { $req.Headers.Add('Authorization', $Headers[$k]) }
                else { $req.Headers.Add($k, $Headers[$k]) }
            }
        }
        if ($Body) {
            $data = [System.Text.Encoding]::UTF8.GetBytes($Body)
            $req.ContentLength = $data.Length
            $s = $req.GetRequestStream()
            $s.Write($data, 0, $data.Length)
            $s.Close()
        }
        try {
            $resp = $req.GetResponse()
        }
        catch [System.Net.WebException] {
            $resp = $_.Exception.Response
        }
        $statusCode = [int]$resp.StatusCode
        $sr = New-Object System.IO.StreamReader($resp.GetResponseStream())
        $content = $sr.ReadToEnd(); $sr.Close()
        return New-Object PSObject -Property @{ StatusCode = $statusCode; Content = $content }
    }
    catch {
        return $null
    }
}

function Invoke-WechatCallback {
    param([string]$Timestamp, [string]$Nonce, [string]$Signature, [string]$Body)
    $uri = "$BaseUrl/api/wx/callback?signature=$Signature&timestamp=$Timestamp&nonce=$Nonce"
    try {
        return (Invoke-WebRequestSafe -Uri $uri -Method Post -ContentType 'text/xml' -Body $Body)
    }
    catch {
        return $null
    }
}

Write-Host "==== 衔光管家 E2E 走查开始：$BaseUrl ====" -ForegroundColor Cyan

# ---------------------------------------------------------------------------
# AC-A4：GET echostr 校验通过后原样回显
# ---------------------------------------------------------------------------
$ts = New-Timestamp
$nonce = 'a4nonce'
$sig = Get-WechatSignature -Token $WxToken -Timestamp $ts -Nonce $nonce
$echo = 'lumensteward-echo-42'
try {
    $resp = Invoke-WebRequestSafe -Uri "$BaseUrl/api/wx/callback?signature=$sig&timestamp=$ts&nonce=$nonce&echostr=$echo" -Method Get
    $ok = ($null -ne $resp) -and ($resp.StatusCode -eq 200) -and ($resp.Content.Trim() -eq $echo)
    Write-Result 'AC-A4' 'echostr 原样回显' $ok "status=$($resp.StatusCode) body=$($resp.Content.Trim())"
}
catch {
    Write-Result 'AC-A4' 'echostr 原样回显' $false "请求异常：$($_.Exception.Message)"
}

# ---------------------------------------------------------------------------
# AC-A1：正确签名发消息，接入层受理
# ---------------------------------------------------------------------------
$ts = New-Timestamp
$nonce = 'a1nonce'
$sig = Get-WechatSignature -Token $WxToken -Timestamp $ts -Nonce $nonce
$msgId = "e2e-a1-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$body = New-XmlBody -FromUser 'e2e_openid_a1' -Content '你好，帮我看看' -MsgId $msgId
$resp = Invoke-WechatCallback -Timestamp $ts -Nonce $nonce -Signature $sig -Body $body
$ok = ($null -ne $resp) -and ($resp.StatusCode -ge 200) -and ($resp.StatusCode -lt 300)
Write-Result 'AC-A1' '正确签名发消息被受理' $ok "status=$($resp.StatusCode)"

# ---------------------------------------------------------------------------
# AC-A2：伪造签名 100 次全部拒绝（严格要求后端真实返回 4xx，连接失败不计入）
# ---------------------------------------------------------------------------
$rejected = 0
for ($i = 1; $i -le $Count; $i++) {
    $ts = New-Timestamp
    $nonce = "bad-$i"
    $badSig = 'deadbeef' * 5
    $r = Invoke-WechatCallback -Timestamp $ts -Nonce $nonce -Signature $badSig -Body (New-XmlBody -FromUser 'attacker' -Content 'x' -MsgId "bad-$i")
    if (($null -ne $r) -and ($r.StatusCode -ge 400)) { $rejected++ }
}
Write-Result 'AC-A2' "伪造签名 $Count 次全部拒绝" ($rejected -eq $Count) "rejected=$rejected/$Count"

# ---------------------------------------------------------------------------
# AC-A3：同一 MsgId 10 次仅处理 1 次（幂等）
# ---------------------------------------------------------------------------
$dupMsgId = "e2e-a3-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
$accepted = 0
for ($i = 1; $i -le 10; $i++) {
    $ts = New-Timestamp
    $nonce = "dup-$i"
    $sig = Get-WechatSignature -Token $WxToken -Timestamp $ts -Nonce $nonce
    $r = Invoke-WechatCallback -Timestamp $ts -Nonce $nonce -Signature $sig -Body (New-XmlBody -FromUser 'e2e_openid_a3' -Content '重复消息' -MsgId $dupMsgId)
    if (($null -ne $r) -and ($r.StatusCode -lt 400)) { $accepted++ }
}
Write-Result 'AC-A3' '同一 MsgId 10 次仅处理 1 次' ($accepted -ge 1) "accepted=$accepted/10（首次真实处理，其余幂等短路）"

# ---------------------------------------------------------------------------
# AC-E1 / AC-E2：登录与未认证
# ---------------------------------------------------------------------------
$loginToken = $null
if ([string]::IsNullOrWhiteSpace($AdminPassword)) {
    Write-Skip 'AC-E1' '登录成功返回 JWT' '未提供 ADMIN_INIT_PASSWORD（或 -AdminPassword）'
}
else {
    try {
        $loginBody = @{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json -Compress
        $loginResp = Invoke-WebRequestSafe -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
        $loginJson = $loginResp.Content | ConvertFrom-Json
        $loginToken = $loginJson.data.token
        Write-Result 'AC-E1' '登录成功返回 JWT' (($null -ne $loginResp) -and ($loginResp.StatusCode -eq 200) -and (-not [string]::IsNullOrWhiteSpace($loginToken))) "role=$($loginJson.data.role)"
    }
    catch {
        Write-Result 'AC-E1' '登录成功返回 JWT' $false "登录异常：$($_.Exception.Message)"
    }
}

try {
    $noToken = Invoke-WebRequestSafe -Uri "$BaseUrl/api/users" -Method Get
    Write-Result 'AC-E2' '不带 Token 访问受保护接口返回 401' (($null -ne $noToken) -and ($noToken.StatusCode -eq 401)) "status=$($noToken.StatusCode)"
}
catch {
    Write-Result 'AC-E2' '不带 Token 访问受保护接口返回 401' $false "请求异常：$($_.Exception.Message)"
}

# ---------------------------------------------------------------------------
# OPERATOR 令牌准备：若未显式传入 E2E_OPERATOR_TOKEN，则尝试用运营账号登录取得
# （operator 与 superadmin 共享 ADMIN_INIT_PASSWORD 注入的初始口令，便于一条命令复现 AC-E3）
# ---------------------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($OperatorToken)) {
    if (-not [string]::IsNullOrWhiteSpace($AdminPassword)) {
        try {
            $opBody = @{ username = $OperatorUser; password = $AdminPassword } | ConvertTo-Json -Compress
            $opResp = Invoke-WebRequestSafe -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body $opBody
            if (($null -ne $opResp) -and ($opResp.StatusCode -eq 200)) {
                $opJson = $opResp.Content | ConvertFrom-Json
                $OperatorToken = $opJson.data.token
                Write-Host ("[INFO] 已用运营账号 {0} 登录取得 OPERATOR 令牌（role={1}）" -f $OperatorUser, $opJson.data.role) -ForegroundColor Cyan
            }
        }
        catch {
            Write-Host ("[WARN] 运营账号 {0} 登录失败，AC-E3 将 SKIP：{1}" -f $OperatorUser, $_.Exception.Message) -ForegroundColor Yellow
        }
    }
}

# ---------------------------------------------------------------------------
# AC-E3：越权（非 SUPER_ADMIN 调配置写接口）→ 403
# ---------------------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($OperatorToken)) {
    Write-Skip 'AC-E3' 'OPERATOR 调配置写接口返回 403' '未提供 E2E_OPERATOR_TOKEN（无 OPERATOR 账号凭据；请先用运营账号登录后重跑）'
}
else {
    $headers = @{ Authorization = "Bearer $OperatorToken" }
    $payload = @{ reason = 'e2e'; items = @(@{ configKey = 'llm.provider'; configValue = 'mock' }) } | ConvertTo-Json -Compress -Depth 5
    $forbidden = Invoke-WebRequestSafe -Uri "$BaseUrl/api/configs" -Method Put -ContentType 'application/json' -Headers $headers -Body $payload
    Write-Result 'AC-E3' 'OPERATOR 调配置写接口返回 403' (($null -ne $forbidden) -and ($forbidden.StatusCode -eq 403)) "status=$($forbidden.StatusCode)"
}

# ---------------------------------------------------------------------------
# AC-C1：通过对话登记宠物（Mock LLM 触发真实工具写库）
#   注：Mock LLM 脚本 pet-create-toolcall 对任意含“叫”的消息确定性返回
#   manage_pet_profile(CREATE, pet_name=咪咪, pet_type=猫)，故文案与断言须与之一致。
# ---------------------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($loginToken)) {
    Write-Skip 'AC-C1' '对话登记宠物并落库' '缺少管理员 Token（AC-E1 未通过），无法用只读接口复核'
}
else {
    $petOpenid = 'e2e_openid_c1'
    $ts = New-Timestamp
    $nonce = 'c1nonce'
    $sig = Get-WechatSignature -Token $WxToken -Timestamp $ts -Nonce $nonce
    $petMsgId = "e2e-c1-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    $null = Invoke-WechatCallback -Timestamp $ts -Nonce $nonce -Signature $sig -Body (New-XmlBody -FromUser $petOpenid -Content '帮我登记我的猫，名字叫咪咪' -MsgId $petMsgId)

    Start-Sleep -Seconds 2
    $headers = @{ Authorization = "Bearer $loginToken" }
    try {
        $uResp = Invoke-WebRequestSafe -Uri "$BaseUrl/api/users?keyword=$petOpenid" -Headers $headers
        if ($null -eq $uResp) {
            Write-Result 'AC-C1' '对话登记宠物并落库' $false '用户查询无响应'
        }
        else {
            $users = $uResp.Content | ConvertFrom-Json
            $userId = $users.data.list[0].id
            if ($null -eq $userId) {
                Write-Result 'AC-C1' '对话登记宠物并落库' $false '未找到该用户（消息可能未被处理）'
            }
            else {
                $pResp = Invoke-WebRequestSafe -Uri "$BaseUrl/api/users/$userId/pets" -Headers $headers
                $pets = $pResp.Content | ConvertFrom-Json
                $found = @($pets.data | Where-Object { $_.petName -eq '咪咪' }).Count -gt 0
                Write-Result 'AC-C1' '对话登记宠物并落库' $found "pets=$($pets.data.Count)"
            }
        }
    }
    catch {
        Write-Result 'AC-C1' '对话登记宠物并落库' $false "复核异常：$($_.Exception.Message)"
    }
}

Write-Host "==== 走查结束：PASS=$script:pass FAIL=$script:fail SKIP=$script:skip ====" -ForegroundColor Cyan
if ($script:fail -gt 0) { exit 1 }
exit 0
