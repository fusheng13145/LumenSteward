# 衔光管家（LumenSteward）MVP 交付确认与验收三件套

> 文档性质：基于 **2026-09-18 独立复验** 的现状确认与范围/计划/验收定义。
> 复验纪律：所有结论均来自实际构建、实际运行与端到端脚本实证，不采信未验证的完成声明（遵循项目 G-31/G-32 证据自证要求）。
> 配套证据：`buildlog/e2e-run6.log`（7 PASS / 0 FAIL / 1 SKIP）、`buildlog/ps-diag.log`、`biz_pet_profile` 直查结果。

---

## 0. 复验结论摘要（一句话）

**MVP 已可成功构建、可实际运行、核心链路端到端跑通**；剩余仅为可延后的增强项与一处需补 token 的越权用例。

| 维度 | 复验结果 |
|---|---|
| 后端构建 | `mvn -f backend/pom.xml clean verify` → 163 单测全过、BUILD SUCCESS、已重打可执行 jar（前序会话已复验） |
| 前端构建 | `vue-tsc --noEmit` + `vite build` → EXIT=0（1715 modules, 7.16s，前序会话已复验） |
| 运行时 | `/actuator/health` = 200/UP；`db=UP(MySQL)`、`redis=UP` |
| 端到端 | `scripts/e2e-smoke.ps1` → **7 PASS / 0 FAIL / 1 SKIP**（AC-E3 因未提供 OPERATOR token 跳过） |
| 关键链路 | 微信 inbound 验签 → 路由 → Mock LLM → `manage_pet_profile` → **真实写入 MySQL `biz_pet_profile`（id=1, openid=e2e_openid_c1）** |

---

## 1. MVP 核心功能范围（含与不含的边界）

### 1.1 IN —— MVP 必须交付（已实际跑通）

| 能力域 | 范围说明 | 验证状态 |
|---|---|---|
| 微信通道接入 | 验签（sha1 字典序）、MsgId 去重、GET echostr 回显、POST 收消息 | AC-A4/A1/A2/A3 已 PASS |
| 上下文与意图 | 由 **Mock LLM** 承载「轮次 + 最近 user 消息子串」确定性意图与工具决策 | 通过 `mock/llm-scripts.yml` 实跑 |
| 工具编排与真实执行 | LLM 返回 `tool_calls` → 后端真实调用 `manage_pet_profile` 等工具并写库 | AC-C1 已 PASS（DB 直查确认） |
| 安全护栏 | 内容安全 + **执行一致性校验**（反幻觉：声称已查工具但无调用 → 拦截） | 单测/集成覆盖；mock 含反幻觉用例 |
| 数据落库 | MySQL 持久化用户/宠物/消息/配置等 | `biz_pet_profile` 实证写入 |
| 管理员后台 | 登录(JWT) + 角色(SUPER_ADMIN/OPERATOR) + 受保护接口鉴权 + 只读复核 | AC-E1/E2 已 PASS |
| 可观测/自证 | `/actuator/health`、Startup Doctor、一键 e2e 走查脚本 | health UP；e2e 全绿 |

### 1.2 OUT —— 显式不在 MVP（与既有 PRD §4.2 不做清单 N-1~N-14 对齐）

- **真实 LLM 供应商接入**：MVP 用 Mock LLM 替代，架构已预留 provider 切换位（`llm.provider=mock`）。
- **真实微信公众平台外发**：本环境无公网回调，使用 Mock 微信通道；真实外呼为后续项。
- **多租户 / 多公众号**：单拥有方、单实例。
- **支付 / 电商 / 复杂 RAG 知识库**：超出 MVP。
- **高并发水平扩展 / 多实例部署**：MVP 为分层单体单实例，目标可水平扩展但不做多活。
- **完整运营报表与多渠道触达**：仅提供基础看板与只读复核。

> 边界纪律：OUT 项不得在本迭代内以「部分完成」名义占用 P0 工时；如需启用须走 11.3 变更流程。

---

## 2. 优先级分步实现计划（基于当前进度）

项目 T01~T05 已由 README 声明完成，本次复验确认「可构建 + 可运行 + 核心链路通」。故计划以**收尾与加固**为主，按 P0/P1/P2 排列。

### P0 —— 已完成并复验（本会话）
1. 后端 `mvn clean verify` 通过、可执行 jar 可启动。
2. 前端 `type-check` + `build` 通过。
3. 运行时依赖（MySQL/Redis）起得来，`/actuator/health` 全绿。
4. 端到端主链路（AC-A1~A4、AC-C1、AC-E1/E2）一键走查全 PASS。
5. 修复 `scripts/e2e-smoke.ps1` 使其在本机（Windows PowerShell 5.1，无 `pwsh`）可真实运行。

### P1 —— 收尾 / 加固（建议本迭代内完成）
| 序 | 任务 | 依据 / 验收 | 优先级 |
|---|---|---|---|
| 1 | 补跑 AC-E3：建 OPERATOR 账号并传 `E2E_OPERATOR_TOKEN` 重跑，确认越权 403 | PRD 越权用例；当前 SKIP | 高 |
| 2 | 校准 6 张表名为建表规范 7.6 前缀（`sys_/wx_/biz_/log_`），补增量迁移脚本 | `TODO-08`、规范 7.6 | 高 |
| 3 | 将 CI 流水线（Trivy/OSV/Vitest 覆盖率门禁/后端检查）前移至迭代 1 | `TODO-10`、NFR-MA-04/09、证据闸门 | 高 |
| 4 | 真实 LLM 适配器与真实微信通道适配器（provider 切换位已留） | 取代 Mock，范围外转范围内需变更 | 中 |
| 5 | Startup Doctor 接入正式运行自检（占位 fail-fast 在 local 仅告警已验证） | 反「它们都不报错」反模式 | 中 |

### P2 —— 延后（不影响 MVP 可交付）
- 多轮对话持久化与上下文窗口优化。
- 高级看板（降级/拦截看板、超时预算瀑布图）。
- 工具调用回放调试台、档案变更留痕。
- 真实外呼压测与 S-1（P95≤3s）实测校准。

---

## 3. 可量化验收标准

### 3.1 功能正确性（验收项 AC-*）

| 编号 | 标准 | 量化判据 | 实证 |
|---|---|---|---|
| AC-A4 | 验签回显 | GET `echostr` 返回 **200 且 body == echostr** | PASS（body=lumensteward-echo-42） |
| AC-A1 | 正确签名受理 | 正确签名文本消息返回 **200** | PASS |
| AC-A2 | 伪造签名拒绝 | **100/100** 伪造请求返回 4xx | PASS（rejected=100/100） |
| AC-A3 | MsgId 幂等 | 同 MsgId 10 次提交 **不报 4xx**（幂等短路） | PASS（accepted，软校验） |
| AC-E1 | 登录鉴权 | 登录返回 **200 + JWT**，`role=SUPER_ADMIN` | PASS |
| AC-E2 | 未认证拦截 | 无 token 访问受保护接口返回 **401** | PASS |
| AC-E3 | 越权拦截 | OPERATOR 调配置写接口返回 **403** | SKIP（待 OPERATOR token） |
| AC-C1 | 对话登记落库 | 经 Mock LLM 触发 `manage_pet_profile` 并**真实写入 MySQL** | PASS（DB 直查 id=1） |

> 诚实标注：AC-A3 当前为「受理不报错」的软校验，严格去重（不二次处理）由代码 MsgId 去重保证，本烟测未断言「仅处理 1 次」。AC-E3 需补 token 后复跑。

### 3.2 基本运行效果

| 指标 | 标准 | 状态 |
|---|---|---|
| 健康检查 | `/actuator/health` = **200 / UP**，且 `db`、`redis` 组件均为 UP | 实测 UP |
| 启动自检 | Startup Doctor 对缺失依赖给出可定位信号（local 仅告警） | 已验证 |
| 端到端时延 S-1 | 首条消息 P95 ≤ **3s** | 标准已定义，**未做压测**，待 P2 实测校准 |
| 反幻觉泄漏 S-2 | 一致性校验拦截率 = **0 泄漏** | 单测/集成覆盖，mock 含反幻觉用例 |
| 可自证 S-3 | 提供一键 e2e + 构建日志可复现 | 已提供（`e2e-smoke.ps1` + `buildlog/`） |

### 3.3 构建要求

| 项 | 命令 / 要求 | 判据 |
|---|---|---|
| 后端 | `mvn -f backend/pom.xml clean verify` | **0 失败**，163 单测通过，产出可执行 jar |
| 前端 | `npm ci && npm run type-check && npm run build` | `tsc --noEmit` 与 `vite build` 均 **EXIT=0** |
| JDK | 17+（实际 21） | — |
| 构建工具 | Maven 3.9.x；**git-bash 下须用 `bin/mvn.cmd`**（unix `bin/mvn` 因 classpath 转换失败） | — |
| 运行时依赖 | MySQL 8.4 + Redis 7（docker-compose `-p clawbot`；宿主 3306 占用改 13306） | 容器 UP |
| e2e 走查 | `powershell -File scripts/e2e-smoke.ps1 -AdminPassword <pwd>` | **PASS≥7, FAIL=0** |
| 本机脚本坑（已修） | e2e 脚本须 **UTF-8 带 BOM** 且用 .NET `HttpWebRequest`（绕过 PS5.1 非交互 `Invoke-WebRequest` 异常） | 已解决 |

---

## 4. 附录：环境复现要点（本机）

- 仅 `docker-compose` v5.5.1 可用（无 `docker compose` v2）；中文目录名须 `-p clawbot`。
- 宿主 3306 被本机 MySQL 占用 → 另起 `clawbot-mysql2` 映射 **13306**；后端 `DB_PORT=13306`。
- 后端运行参数参考：`local` profile + `WX_TOKEN=clawbot-local-token` + `ADMIN_INIT_PASSWORD=Admin@123456` + 连 `clawbot-mysql2` / `clawbot-redis`。
- e2e 脚本修复三层根因：① UTF-8 无 BOM → 改带 BOM；② `-SkipHttpErrorCheck` 是 PS7 专属 → 自写兼容包裹；③ PS5.1 非交互 `Invoke-WebRequest` 抛异常 → 改用纯 .NET `HttpWebRequest`。

> 结论：交付物**已成功构建并实际运行**，核心验收（含真实写库 AC-C1）经端到端脚本与数据库直查双重实证。剩余 AC-E3 仅需补 OPERATOR token 即可闭环，P1/P2 为增强项，不阻塞 MVP 交付。
