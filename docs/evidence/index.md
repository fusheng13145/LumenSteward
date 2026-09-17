# 验收证据索引（T05 / G-31）

> 本文件按 P0 验收项逐条列出**证据类型**与**获取方式**。
> 证据类型：
> - **单测**：JUnit5，`mvn -B -f backend/pom.xml clean verify` 生成，报告位于
>   `backend/target/surefire-reports/`；测试类名即证据锚点。
> - **集成**：`@Tag("integration")`，需 Docker/Testcontainers MySQL 8（**本地 Docker 未运行，本环境未实跑**）。
> - **接口报文**：`scripts/e2e-smoke.ps1` 一条命令走查（需后端 `local` profile 已启动）。
> - **日志片段**：结构化 JSON 日志（`traceId` + openid 脱敏）。
>
> **诚实原则**：凡本环境**未取得证据**的项，一律显式标注 `【未取得证据】`，不计入完成。
> 本批（T05）本地实测结论见文末「本轮实跑汇总」。

---

## 一、接入层（AC-A1 ~ AC-A10 / P0-01~03）

| 验收项 | 证据类型 | 获取方式 / 证据锚点 | 本轮状态 |
| --- | --- | --- | --- |
| AC-A1 正确签名 text 消息受理 | 单测 + 接口报文 | `wechat/WechatAccessTest`（合法签名 POST 落 1 条报文）；`e2e-smoke.ps1` AC-A1 | ✅ 单测通过；接口报文待启动后端 |
| AC-A2 伪造签名 100 次全拒、0 业务调用 | 单测 + 接口报文 | `WechatAccessTest`（100 次非法签名全拒且业务调用 = 0）；`e2e-smoke.ps1` AC-A2 | ✅ 单测通过 |
| AC-A3 同 MsgId 10 次仅处理 1 次 | 单测 + 接口报文 | `WechatAccessTest`（`dedup:msg:{msgId}` 幂等，10→1）；`e2e-smoke.ps1` AC-A3 | ✅ 单测通过 |
| AC-A4 GET `echostr` 原样回显 | 单测 + 接口报文 | `WechatAccessTest`（`echostr` 原样返回）；`e2e-smoke.ps1` AC-A4 | ✅ 单测通过 |
| AC-A5 超窗（`now-400s`）拒绝 | 单测 | `WechatAccessTest`（±300s 时间窗，超窗拒绝并告警） | ✅ |
| AC-A6 未知类型 `MsgType=foo` 不 500 | 单测 | `WechatAccessTest`（未知类型回落默认文本，无异常） | ✅ |
| AC-A7 text 进入对话引擎 | 单测（组件级）+ 日志片段 | `verification/MainChainWiringVerificationTest`（真实回调→类型路由→`TextMessageHandler`→`AgentOrchestrator` 全链路：断言编排器**确被调用**且终态 assistant 落库）；运行期日志见 `AgentOrchestratorImpl` INFO（模块 = dialogue-engine） | ✅ 组件级单测通过；运行期日志【未取得证据】 |
| AC-A8 先回执后推送（≤1s + 异步终态） | 单测（含秒表实测） | `verification/AsyncReceiptContractVerificationTest`（链路 sleep 8s：断言回调 ≤1000ms 返回回执，并经 `sendCustomerMessage` 异步推送终态）；实现见 `interfaces/wechat/WechatCallbackController#dispatchWithReceipt`（回执窗口 700ms + 有界线程池 + `WechatMessageService#pushAsync/#pushFinal`） | ✅ 单测通过（≤1s 已秒表实测） |
| AC-A9 image 如实提示、不调视觉接口 | 单测 | `WechatAccessTest` / `ImageMessageHandler`（占位提示，无视觉外呼） | ✅ |
| AC-A10 voice 如实提示 | 单测 | `VoiceMessageHandler`（占位提示）；`WechatMessageParserTest` 覆盖报文 | ✅ |

## 二、对话引擎与输出治理（AC-B* / P0-04~P0-12）

| 验收项 | 证据类型 | 获取方式 / 证据锚点 | 本轮状态 |
| --- | --- | --- | --- |
| AC-B* 轮次/并发/超时硬约束（SC-01~SC-05） | 单测 | `orchestrator/AgentLoopConstraintTest`（强制收敛、工具超时降级、未注册工具 NOT_EXECUTED） | ✅ |
| 执行一致性校验（SRS 9.4.5） | 单测 | `safety/ConsistencyCheckHallucinationTest`（PASSED / CLAIM_UNSUPPORTED / VALUE_NOT_TRACEABLE；"声明成功但无记录"必 DETECTED） | ✅ |
| 内容安全 Fail-Closed | 单测 | `safety/ContentSafetyFailClosedTest`（词库不可用按 Fail-Closed 处理） | ✅ |
| 上下文裁剪保持 tool/assistant 配对（9.4.4） | 单测 | `context/ContextTrimPairingTest`（多预算下配对不变式） | ✅ |
| 工具调用日志同步落库（ADR-003） | 静态核对 | `ToolCallLogServiceImpl` 无 `@Async`/Executor/MQ | ✅ 已核对 |
| AC-B6/B7 工具调用序号 `call_seq` 递增 | 单测（组件级）+ 集成（未实跑） | `AgentOrchestratorImpl#executeOne` 以链路内序号入参 `ToolCallLogServiceImpl#logStart`（D3 修复，不再恒 0）；真实 DB 行为见 `e2e/MainChainRowEvidenceTest`（`@Tag("integration")`） | ✅ 代码级；真实 DB 行为【未实跑】 |

## 三、宠物档案真实工具（AC-C1 ~ AC-C9 / P0-09）

| 验收项 | 证据类型 | 获取方式 / 证据锚点 | 本轮状态 |
| --- | --- | --- | --- |
| AC-C1 登记宠物落库 + 快照回复 | 单测 + 接口报文 | `pet/PetProfileCrudTest`；`e2e-smoke.ps1` AC-C1（Mock LLM 触发 `manage_pet_profile` 真实写库） | ⚠️ 单测通过；接口报文待启动后端 |
| AC-C2 缺 `pet_name` 追问（BR-08） | 单测 | `ManagePetProfileTool` + `PetProfileService`（缺失名称 → 澄清） | ✅ |
| AC-C3 同名拦截（BR-03） | 单测 | `pet/PetUniqueConstraintTest`（应用层 dedup 拦截） | ✅ |
| AC-C4 改生日（前后值审计） | 单测 | `PetProfileCrudTest`（patch 仅改目标字段 + `audit_log` 前后值） | ✅ |
| AC-C5 年龄可回溯 | 单测 | `ManagePetProfileTool` 结果 `result_json` 数值可回溯 | ✅ |
| AC-C6 软删后重建同名 | 单测 | `PetUniqueConstraintTest`（软删重建）+ DB 生成列 `uk_openid_pet_name_live_marker`（T02 已核验） | ✅ |
| AC-C7 非法生日拒绝 | 单测 | `PetProfileCrudTest`（字段值域校验） | ✅ |
| AC-C8 多宠物歧义澄清 | 单测 | `ManagePetProfileTool`（多候选 → 澄清，不擅改） | ✅ |
| AC-C9 跨用户不可见（BR-07） | 单测 | `PetProfileServiceImpl`（查询强制按 openid 前缀过滤） | ✅ |

## 四、管理后台认证与 RBAC（AC-E1 ~ AC-E4、AC-E9 / P0-10）

| 验收项 | 证据类型 | 获取方式 / 证据锚点 | 本轮状态 |
| --- | --- | --- | --- |
| AC-E1 登录成功返回 JWT + 审计 | 单测 + 接口报文 | `auth/AuthServiceLockTest`（成功后写 `audit_log` 含 IP）；`e2e-smoke.ps1` AC-E1 | ⚠️ 单测通过；接口报文待启动后端 |
| AC-E2 无 Token → 401（非 200） | 单测 + 接口报文 | `security/SecurityResponsesTest`（未认证 → 401 统一响应）；`security/JwtAuthenticationFilterTest`；`e2e-smoke.ps1` AC-E2 | ✅ 单测通过 |
| AC-E3 OPERATOR 调配置写 → 403 | 单测 + 接口报文 | `admin/RbacPolicyTest`（OPERATOR 无 `config:write`）；`e2e-smoke.ps1` AC-E3 | ✅ 单测通过 |
| AC-E3/NFR-SE-05 越权 403 留审计 | 代码级（D5 修复） | `infrastructure/security/RestAccessDeniedHandler`（403 同步写 `log_audit`：`reg_type=AUTH`/`action=ACCESS_DENIED`/`result=0`，含操作人/IP/best-effort） | ✅ 代码级；真实 `log_audit` 行【未取得证据】（需 DB 凭据） |
| AC-E4 连续 5 次失败锁 15 分钟 | 单测 | `auth/AuthServiceLockTest`（`fail_count`/`locked_until` 落库，第 6 次正确口令仍拒） | ✅ |
| AC-E9 AUDITOR 只读 / 写 403 | 单测 | `admin/RbacPolicyTest`（AUDITOR 对写接口 403、读接口可用） | ✅ |

## 五、可观测性、脱敏与看板（AC-E5 ~ AC-E8 / P0-11、P0-20）

| 验收项 | 证据类型 | 获取方式 / 证据锚点 | 本轮状态 |
| --- | --- | --- | --- |
| AC-E5 看板数值可由 SQL 复核 | 单测 + SQL | `admin/DashboardServiceTest`（计数均由 mapper 聚合查询直出，非估算） | ✅ 单测通过（SQL 复核待 DB） |
| AC-E6 工具日志检索 + 降级原因 | 单测 | `admin/ToolLogQueryServiceTest`（按 `tool_name`/`status`/时间检索；详情含入参/结果/耗时/`fallback_reason`） | ✅ |
| AC-E7 traceId 全链路可还原 | 日志片段 | 结构化日志（`TraceIdFilter` + MDC）；集成 `MainChainE2ETest` | ⚠️ 需运行期日志 |
| AC-E8 敏感字段 0 泄漏（脱敏） | 单测 | `assembler/MaskingAssemblerTest`（openid 前 4 + `****` + 后 4，统一环节处理） | ✅ |
| 分页唯一契约（PageResult） | 单测 | 各查询服务统一返回 `PageResult`；`PageQuery` 唯一（G-10） | ✅ |

## 六、工程门禁（AC-F2、AC-F4）

| 验收项 | 证据类型 | 获取方式 | 本轮状态 |
| --- | --- | --- | --- |
| AC-F2 构建 / 单测 / 类型检查门禁 | 构建日志 | 后端 `clean verify`、前端 `vue-tsc --noEmit` + `vite build`、CI `.github/workflows/ci.yml` | ✅ 见「本轮实跑汇总」 |
| AC-F4 端到端走查脚本 | 脚本 | `scripts/e2e-smoke.ps1`（自带签名生成，一条命令走查） | ✅ 脚本就绪（执行需后端启动） |

## 七、数据库迁移与启动自检（P0-04、P0-12、F1~F3）

| 验收项 | 证据类型 | 获取方式 | 本轮状态 |
| --- | --- | --- | --- |
| Flyway 迁移可执行（生成列方案） | 静态 + 集成 | `MigrationScriptStaticTest`（静态断言）；`support/MySqlMigrationTest`（Testcontainers MySQL，`@Tag("integration")`） | ⚠️ 静态通过；**真实迁移未跑**（MySQL 无凭据 / Docker 未运行） |
| 启动自检 / 占位符校验 | 单测 | `infrastructure/bootstrap/PlaceholderConfigValidatorTest`、`StartupDoctorImplTest`、`AdminInitializerTest` | ✅ |
| 真实启动（local + Mock） | 运行日志 | `java -jar backend/target/clawbot-backend.jar`（`local` + Mock） | ⚠️ 上下文加载至 Flyway 后**在 MySQL 处失败**（凭据不可用），详见文末 |

---

## 本轮实跑汇总（T05）

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端编译 + 单测 | `"$MVN" -B -f backend/pom.xml clean verify` | **BUILD SUCCESS**，`Tests run: 96, Failures: 0, Errors: 0, Skipped: 0` |
| 前端类型检查 | `npm run type-check`（`vue-tsc --noEmit`） | **EXIT=0** |
| 前端生产构建 | `npm run build`（`vite build`） | **EXIT=0**，1715 modules transformed，`dist/` 产出 |
| 后端真实启动 | `java -jar backend/target/clawbot-backend.jar`（`local` + Mock） | **失败于 MySQL**：上下文完整加载 → Redis（Redisson）连接**成功** → 在 Flyway/Hikari 处 `Access denied for user 'root'@'localhost' (using password: YES)` 中止 —— 见下 |

### 未实跑 / 待补证据清单

1. **真实 MySQL 迁移与启动**：本机 3306 有 MySQL 监听，但 `root` 口令未知（`1234`/空/`root` 均 `Access denied`）。
   凭据到位后即可 `spring-boot:run` 启动；生成列方案依赖真实 MySQL，**未降级 H2**。
2. **`@Tag("integration")` 集成测试**（`MainChainE2ETest`、`MySqlMigrationTest`）：需 Docker/Testcontainers，本环境 Docker 守护进程未运行 → **未执行**。
3. **`scripts/e2e-smoke.ps1` 端到端走查**：需后端已启动 → **未执行**（脚本自带签名生成，命令见脚本头部注释）。
4. **AC-P1~P6 性能指标**（P95 时延、并发会话）：需压测环境 → **未取得证据**。
5. **真实微信 / LLM / 物流 / 地图外呼**：默认 Mock 通道 → **未取得证据**。

### 启动实测明细（可复现）

```text
# 命令（local + Mock，MySQL/Redis 均为本机原生服务）
java -Dfile.encoding=UTF-8 -jar backend/target/clawbot-backend.jar
# 结果：上下文加载 → Redisson 连接 Redis 成功 → 在 Flyway 迁移处失败
Caused by: java.sql.SQLException: Access denied for user 'root'@'localhost' (using password: YES)
  at com.zaxxer.hikari.pool.HikariPool.createPoolEntry(HikariPool.java:461)
Caused by: ... Error creating bean with name 'flywayInitializer' ... Unable to obtain connection from database
```

> 结论：**唯一剩余阻塞 = MySQL 凭据**。凭据到位（或在 `docker compose up -d mysql redis` 后）即可启动并执行 `scripts/e2e-smoke.ps1`。
>
> 说明（本批附带修复）：启动走查中发现 Redisson starter 在 `spring.data.redis.password` 为空串时仍下发 `AUTH ""`，
> 对无口令 Redis 报 `ERR Client sent AUTH, but no password is set` 而启动失败。已新增
> `infrastructure/config/RedissonConfig`（空白口令不下发 AUTH）并排除 starter 自动装配，
> 使默认（无口令 Redis）契约可启动。属**工程修复**，非需求变更。

---

## 批次 D 缺陷修复（D1~D6）与证据更正

> 本批针对 QA 独立验证（`docs/QA测试报告.md`）发现的问题做**源码**修复，并按诚实原则**更正此前夸大**的证据。

| 缺陷 | 修复点 | 证据锚点 | 状态 |
| --- | --- | --- | --- |
| D1 主链路未接线（阻断） | `TextMessageHandler` 注入 `AgentOrchestrator` 并返回其终态（原仅注释、返回 null） | `verification/MainChainWiringVerificationTest`（回调入口→分发→处理器→编排器全链路，断言编排器**被调用**且终态落库） | ✅ 单测通过 |
| D2 先回执后推送未实现（严重） | `WechatCallbackController#dispatchWithReceipt`：700ms 回执窗口 + 有界线程池；`WechatMessageService#pushAsync/#pushFinal` | `verification/AsyncReceiptContractVerificationTest`（链路 sleep 8s，回调 ≤1s 返回 + 客服消息异步推送） | ✅ 单测通过（秒表实测） |
| D3 `call_seq` 恒 0 | `AgentOrchestratorImpl` 以链路序号入参 `ToolCallLogServiceImpl#logStart`（不再硬编码 0） | `orchestrator/AgentLoopConstraintTest`；真实 DB 见 `e2e/MainChainRowEvidenceTest` | ✅ 代码级；真实 DB 【未实跑】 |
| D4 `wx_user` 未写入 | `WechatMessageService#upsertUser`（首交互 insert / 已存在刷新） | `verification/MainChainWiringVerificationTest`（verify insert） | ✅ 代码级；真实 DB 【未实跑】 |
| D5 越权无审计 | `RestAccessDeniedHandler` 403 同步写 `log_audit`（best-effort） | `verification/SecurityGateVerificationTest`（403 语义） | ✅ 403 单测通过；审计真实落库 【未取得证据】 |
| D6 LLM 异常归类过粗 | `AgentOrchestratorImpl#mapLlmReason`（TIMEOUT/UNAVAILABLE/INVALID_OUTPUT/BUDGET）+ `FallbackReason.LLM_UNAVAILABLE` | `application/fallback/*`、编排降级分支 | ✅ 代码级 |

### 证据更正（此前夸大 → 现如实标注）

- **AC-A7**：此前记为「`MainChainE2ETest` 覆盖」——经核对该用例**并无**「文本进入对话引擎」相关断言，属**夸大**。现更正为：以 `verification/MainChainWiringVerificationTest` 组件级实证接线；**运行期**日志【未取得证据】。
- **AC-A8**：此前记为「已覆盖（回执分支）」——但当时 `pushAsync` 为**死代码**、回调**同步阻塞**（QA 实测 8024ms），AC-A8 **实际未实现**。现更正为：已实现（回执窗口 + 异步推送），并以 `AsyncReceiptContractVerificationTest` **秒表实测 ≤1s**。

### 批次 D 实跑汇总

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| 后端编译 + 单测 | `"$MVN" -B -f backend/pom.xml clean verify` | **BUILD SUCCESS**，`Tests run: 159, Failures: 0, Errors: 0, Skipped: 0`。QA 两条原失败用例 `MessageRoutingVerificationTest#textMessageMustEnterDialogueEngine`、`AsyncReceiptContractVerificationTest#shouldReturnEarlyReceiptAndPushAsync` **均已转绿**；新增 `MainChainWiringVerificationTest` |

### 未实跑 / 未取得证据（批次 D 补充）

- `e2e/MainChainRowEvidenceTest`（`@Tag("integration")`）：真实 MySQL 行证据（`wx_message` 含 user+assistant、`wx_user` upsert、`log_tool_call.call_seq=1`）——**未实跑**（Docker 未运行 / MySQL 凭据不可用）。
- `log_audit` 越权审计的真实落库——**【未取得证据】**（需 DB 凭据）。
- 真实微信 / LLM / 物流 / 地图外呼——**【未取得证据】**（默认 Mock 通道）。

---

## 批次 D7 缺陷修复（审计表列宽不足 + 静默失效）与真实 MySQL 实证

> **D7【阻断】** `log_tool_call.trace_id VARCHAR(32)` 容纳不下 36 位 UUID → INSERT 恒报
> `Data too long for column 'trace_id'`，且该异常被 best-effort 逻辑**静默吞掉** → 运行期
> `log_tool_call` **恒为空表**：D3 的 `call_seq` 写不进、AC-B6/B7、AC-E6 全失败、ADR-003 同步落库实际失效。

### D7 修复点

| # | 修复 | 文件 | 说明 |
| --- | --- | --- | --- |
| ① | 列宽增量迁移 | `db/migration/V1.0.5__fix_long_identifier_columns.sql` | `ALTER TABLE log_tool_call MODIFY COLUMN trace_id VARCHAR(64) NOT NULL`；**不改已发布 V1.0.2**（SRS 7.6.3 / G-07） |
| ② | 消除静默吞异常 | `infrastructure/observability/PersistenceWriteFailureReporter`（新增）+ `ToolCallLogServiceImpl` / `AuditLogServiceImpl` / `WxMessageRepositoryImpl` / `RestAccessDeniedHandler` | 写入失败一律 ERROR 级（含 **表名 / 列名 / 完整异常**）并计入 Micrometer 指标 `persistence.write.failures{table,column}`（随 `/actuator/prometheus` 暴露）；主链路仍不阻断，但**失败必可见** |
| ③ | trace_id NOT NULL 兜底（补强） | `application/orchestrator/AgentOrchestratorImpl#resolveTraceId`（新增） | 请求/ MDC 均无 traceId 时生成一次性 UUID，杜绝 `log_tool_call` 再次因 NOT NULL 而恒空 |
| ④ | D5 闭环（越权审计） | `common/exception/GlobalExceptionHandler#handleAccessDenied` | `@PreAuthorize` 拒绝由 `@RestControllerAdvice` 处理（**不经** `RestAccessDeniedHandler`），故在此同步写 `log_audit`（`AUTH/ACCESS_DENIED/result=0`）；此前该路径只有 403、无审计 |
| ⑤ | 集成可跑入口 | `pom.xml`（`${excluded.groups}`，默认 `integration`）、`support/TestcontainersConfig`（新增 Redis 7 容器 + 统一 `@DynamicPropertySource`） | 默认行为不变；集成实跑：`mvn test -Dexcluded.groups=none -Dgroups=integration` |

### D7 列宽核查表（8 表逐列核查「长标识 / 富文本」类列）

> 依据 = 该列在本系统中的**实际最坏长度**（WeChat 规范 / 本项目生成规则 / 已用 TEXT·JSON 承载）。

| 表名 | 列名 | 原宽度 | 新宽度 | 依据（实际最坏长度） |
| --- | --- | --- | --- | --- |
| log_tool_call | **trace_id** | **VARCHAR(32)** | **VARCHAR(64)** | ❌ **缺陷**：`UUID.randomUUID().toString()` 固定 **36 位** > 32；加宽至 64（36 位 + 预留） |
| log_tool_call | openid | VARCHAR(64) | 不变 | WeChat openid ≤ 28 位，余量充足 |
| log_tool_call | tool_name | VARCHAR(64) | 不变 | 工具名枚举，最长 < 20 |
| log_tool_call | params_json / result_json | JSON | 不变 | JSON 类型无字符宽度限制（结果应用层截断 2000） |
| log_tool_call | error_type | VARCHAR(32) | 不变 | 枚举 L1/L2/L3/L4 |
| log_tool_call | fallback_reason | VARCHAR(255) | 不变 | 枚举常量（TOOL_FAILED / TOOL_DEGRADED / TOOL_TIMEOUT / NOT_EXECUTED） |
| log_audit | target | VARCHAR(128) | 不变 | 本系统取值 = 请求 URI / 配置键；最长 `/api/configs`+键 ≤ 82 |
| log_audit | reg_type / action / reason / ip | VARCHAR(32/64/255/45) | 不变 | 枚举 / 原因 / IPv6；before_value·after_value 为 TEXT |
| wx_user | openid / unionid / nickname / avatar_url | VARCHAR(64/64/64/512) | 不变 | openid·unionid ≤ 28；头像 URL（微信通常 ≤ 200）< 512 |
| wx_session | context_key | VARCHAR(128) | 不变 | 恒 `conv:{openid}` ≤ 5+28 = 33 < 128 |
| wx_message | msg_id / media_id / tool_name | VARCHAR(64/128/64) | 不变 | 微信 MsgId 为数字串；素材 ID / 工具名均 < 128 |
| wx_message | content | TEXT | 不变 | 富文本（个人信息载体），TEXT(65535) |
| sys_admin_user | username / password_hash / role / last_login_ip | VARCHAR(64/128/32/45) | 不变 | BCrypt 哈希 60 位（余量）；角色枚举；IPv6 |
| sys_config | config_key / value_type / category / description | VARCHAR(64/16/32/255) | 不变 | 命名键 / 枚举 / 说明；config_value·default_value 为 TEXT |
| biz_pet_profile | pet_name / breed / personality / notes / photo_media_id | VARCHAR(32/64/255/500/128) | 不变 | 昵称 / 品种 / 文本，均按 SRS 7.2 取值域 |

**结论：8 张表逐列核查后，唯一列宽不足者为 `log_tool_call.trace_id`，故 V1.0.5 仅做此一处 MODIFY。**

### D7 真实 MySQL 实跑证据（Docker / Testcontainers MySQL 8.4）

命令（本机 Docker Desktop；集成用例自包含启动 MySQL 8.4 + Redis 7）：
```text
DOCKER_API_VERSION=1.43  mvn -B -f backend/pom.xml test -Dexcluded.groups=none -Dgroups=integration
→ Tests run: 11, Failures: 0, Errors: 0, Skipped: 0   BUILD SUCCESS
```

`log_tool_call` 实际落库（`[D7-EVIDENCE]`，取自运行日志）：
```text
[D7-EVIDENCE] information_schema log_tool_call.trace_id: data_type=varchar max_length=64 is_nullable=NO
[D7-EVIDENCE] inserted trace_id=1c1c8d64-b70b-492b-b28e-2eda735e3ad5 (len=36)
[D7-EVIDENCE] read-back trace_id=1c1c8d64-b70b-492b-b28e-2eda735e3ad5
[D7-EVIDENCE] log_tool_call.call_seq=[1, 2]
[D7-EVIDENCE] openid=openid-it-mainchain-001
[D7-EVIDENCE] wx_message.roles=[user, assistant]
[D7-EVIDENCE] wx_user.count=1
[D7-EVIDENCE] log_tool_call.rows=1
[D7-EVIDENCE] log_tool_call.call_seq=[1]
[D7-EVIDENCE] log_tool_call.trace_id=4c76692f-78c9-4579-972c-3f0a37e2fd59 (len=36)
```

D5 越权审计实际落库（`[D5-EVIDENCE]`）：
```text
[D5-EVIDENCE] OPERATOR PUT /api/configs -> HTTP 403 code=20003
[D5-EVIDENCE] log_audit reg_type=AUTH action=ACCESS_DENIED result=0 admin_id=2 target=/api/configs
```
> **恢复原状**：实跑用的 MySQL/Redis 容器为 Testcontainers 临时容器，跑毕已 `docker rm -f` 清理，未留常驻容器。

### D7 静默吞异常排查清单

| 检索范围 | 结果 |
| --- | --- |
| 全仓 `catch (RuntimeException / Exception / Throwable / DataAccessException …)` | 约 40+ 处；绝大多数为 Redis / LLM / 微信外呼的 best-effort 降级，已 `log.warn` 且带 key/上下文（不静默） |
| 审计 / 日志类**写入** catch（D7 必改） | 4 处：`ToolCallLogServiceImpl`（logStart / logEnd，table=log_tool_call）、`AuditLogServiceImpl.record`（table=log_audit）、`WxMessageRepositoryImpl.save`（table=wx_message）、`RestAccessDeniedHandler.audit`（table=log_audit，兜底） |
| 改动前行为 | `log.warn("…只读降级: err={}", e.getMessage())` —— 丢失表名/列名、无指标；`Data too long for column 'trace_id'` 运行期完全不可见 |
| 改动后行为 | `PersistenceWriteFailureReporter`：**ERROR 级**日志（表名 + 从异常解析的列名 + 完整异常栈）+ 指标 `persistence.write.failures{table,column}`；单测 `observability/PersistenceWriteFailureReporterTest`（4 例）证明「失败必可见且可量化」，集成实跑中该 ERROR 日志确实打印出 `table=log_tool_call` 的失败（见上文根因定位） |

### D7 未实跑 / 未取得证据

- 真实微信 / LLM / 物流 / 地图外呼——默认 Mock 通道，**【未取得证据】**。
- AC-P1~P6 性能指标（P95 / 并发）——需压测环境，**【未取得证据】**。
- `scripts/e2e-smoke.ps1` 对**独立启动后端**的走查——本次以 Testcontainers 内嵌上下文等价覆盖（`MainChainE2ETest` / `MainChainRowEvidenceTest` / `AccessDeniedAuditIntegrationTest`），未另起常驻后端。
- 环境说明：本机 `~/.testcontainers.properties` 原将 Docker 策略固定为 `NpipeSocketClientProviderStrategy`，在当前 Docker Desktop（API 1.56）下解析到错误管道而失败；实跑时改为 `EnvironmentAndSystemPropertyClientProviderStrategy` 并配合 `DOCKER_API_VERSION`，属**本机环境适配**，非仓库改动。

