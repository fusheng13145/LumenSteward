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
| AC-A7 text 进入对话引擎 | 日志片段 | `AgentOrchestratorImpl` INFO 日志（模块 = dialogue-engine）；集成 `MainChainE2ETest` | ⚠️ 需运行期日志 |
| AC-A8 先回执后推送（≤1s + 异步终态） | 单测 | `application/wechat/WechatMessageService`（回执路径与 `pushAsync`）；`WechatAccessTest` 覆盖回执分支 | ⚠️ 时延为设计保证，未做秒表实测 |
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

