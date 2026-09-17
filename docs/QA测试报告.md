# QA 测试与验收报告 — Claw 助手（LumenSteward）MVP

| 项 | 内容 |
| --- | --- |
| 报告人 | 严过关（软件团队 · QA 工程师） |
| 日期 | 2026-09-17 |
| 被验证对象 | 后端 `backend/`（204 个 main 源文件）、前端 `frontend/`、DDL `db/migration/`、证据索引 `docs/evidence/index.md` |
| 基线 | SRS-CLAWBOT-002 V2.2（只读） + `docs/MVP产品需求文档.md`（AC-A/B/C/D/E/F/AC-P） |
| 验证代码 | `backend/src/test/java/com/lumensteward/clawbot/verification/`（14 个独立测试类，62 个用例，**QA 独立编写，未复用工程师测试类**） |
| 结论记号 | `通过`（有实证） / `失败`（有反证） / `无法验证`（缺环境，写明缺什么） |

> **诚实声明（G1 证据门禁）**：本报告严格区分「通过 / 失败 / 无法验证」。凡本机未取得实证者一律标注「无法验证」，**绝不计为通过**。

---

## 1 验证范围与方法

### 1.1 环境限制（实测）

| 项 | 事实 | 对验证的影响 |
| --- | --- | --- |
| MySQL | 3306 监听，但 `root` 口令未知（`1234`/空/`root`/`123456` 均 Access denied），无凭据文件 | **一切需真实 DB 的操作无法执行**：Flyway 迁移、落库断言、看板/SQL 比对、登录成功审计、唯一索引并发兜底 |
| Docker | 客户端在、**daemon 未运行** | Testcontainers（`MySqlMigrationTest`、`MainChainE2ETest`）不可用 |
| Redis | `127.0.0.1:6379` **可连、无口令（PONG）** | **幂等去重、限流已用真实 Redis 实跑**（见 AC-A3/BR-29） |
| Maven | 用 wrapper：`/c/Users/ASUS/.m2/wrapper/dists/.../apache-maven-3.9.15/bin/mvn.cmd` | 构建与测试均成功 |
| JDK | 21（编译目标 17） | — |

### 1.2 方法

1. **独立编写测试**（14 类 / 62 用例，包 `...clawbot.verification`）：针对验收标准自建断言，覆盖工程师易产生"自我确认偏差"的"不报错但可能失效"的行为。
2. **代码审查**：权限矩阵是否有未加保护的后台端点、明文密钥、日志脱敏、`excludedGroups` 排除项的诚实性、以及"是否真的接线"。
3. **实跑**：`"$MVN" -B -f backend/pom.xml test`。

### 1.3 实跑结果（可复现）

```
命令：  "$MVN" -B -f backend/pom.xml test
结果：  Tests run: 158, Failures: 2, Errors: 0, Skipped: 0  →  BUILD FAILURE
        报告：backend/target/surefire-reports/
```

- 工程师原有 96 个单测：**96 通过**（QA 未改动其代码，独立重跑确认）。
- QA 新增 62 个：**60 通过，2 失败**。
- 2 个失败即**源码缺陷的反证**（非测试缺陷），见 §3。

---

## 2 逐条验收结论

### 2.1 段 A：微信接入层

| AC | 结论 | 证据 |
| --- | --- | --- |
| AC-A1 正确签名受理 | **部分通过** | 验签层：`WechatSignatureVerificationTest.validSignatureAccepted`（通过）。**落库 1 条 / 业务处理 = 1 无法验证**（无 DB）。 |
| AC-A2 伪造签名 100 次全拒、0 业务调用 | **通过** | `WechatSignatureVerificationTest.forgedSignatureRejected100Times`：**100/100 被拒**。控制器先 `signatureVerifier.verify()` 再解析/分发（`WechatCallbackController.receive` 第 115 行），故验签失败时业务与 LLM 调用=0（代码路径确证）。 |
| AC-A3 同 MsgId 10 次仅处理 1 次 | **通过（实跑真实 Redis）** | `RedisLiveCacheVerificationTest.dedupTenToOne`：真实 Redis 下 10 次仅 **1** 次受理。 |
| AC-A4 GET echostr 原样回显 | **通过** | `WechatEchoVerificationTest.echostrEchoedVerbatim`（`"abc123"` 原样返回）；验签失败时不回显（`...NotReturnedWhenSignatureInvalid`）。 |
| AC-A5 超窗（±300s）拒绝 | **通过** | `WechatSignatureVerificationTest.outOfWindowRejected` / `futureOutOfWindowRejected`。 |
| AC-A6 未知类型不 500 | **通过** | `MessageRoutingVerificationTest.unknownTypeFallsBackToText`（`foo` 回落默认文本，不抛异常）。 |
| **AC-A7 text 进入对话引擎** | **失败** | `MessageRoutingVerificationTest.textMessageMustEnterDialogueEngine` **失败**：真实 `TextMessageHandler.handle()` 恒返回 `null`，`MessageDispatcher.dispatch(text)` 返回 `null`。文本消息**未产出任何业务回复**——见缺陷 **D1**。 |
| **AC-A8 先回执后推送（≤1s + 异步终态）** | **失败** | `AsyncReceiptContractVerificationTest` **失败**：链路 sleep 8s 时回调被**同步阻塞 8024ms** 才返回终态，且从不触发客服消息异步推送（`pushAsync` 为死代码）——见缺陷 **D2**。 |
| AC-A9 image 如实提示、不调视觉接口 | **通过** | `MessageRoutingVerificationTest.imagePlaceholder`；`ImageMessageHandler` 无任何 LLM/视觉依赖（结构上不可能外呼）。 |
| AC-A10 voice 如实提示 | **通过** | `MessageRoutingVerificationTest.voicePlaceholder`。 |

### 2.2 段 B：对话引擎与输出治理

> **系统级前置**：因 **D1（主链路未接线）**，段 B 的多数场景**无法经回调端点端到端触发**；其中"纯组件"能力（一致性校验、内容安全、上下文配对）已由 QA 独立验证为**通过**。

| AC | 结论 | 证据 / 缺失原因 |
| --- | --- | --- |
| AC-B1 上下文 5 轮记忆 | **无法验证** | 需 DB 落库 + 主链路接线；`RedisContextStore` 键 `conv:{openid}` 就绪（代码审查）。 |
| AC-B2 用户 A/B 隔离 | **无法验证** | 同上（键按 openid 分区，代码审查：`conv:{openid}`）。 |
| AC-B3 DEL conv 后按首轮处理 | **无法验证** | 端到端未接线；`ContextStore` 空载入返回空列表（代码审查）。 |
| AC-B4/B5/B6/B7 意图/工具/顺序 | **无法验证** | 主链路未接线（D1）；且 `call_seq` 恒 0（D3）。 |
| AC-B8 未注册工具回注 | **无法验证** | 端到端未接线。 |
| AC-B9 缺必填参数回注 | **无法验证** | 端到端未接线。 |
| AC-B10 工具超时降级 | **无法验证** | 端到端未接线。 |
| AC-B11/B12 轮次/并行硬约束 | **无法验证** | 端到端未接线。 |
| **AC-B13 执行一致性拦截** | **通过（组件级）** | `AntiHallucinationVerificationTest`：无成功记录 → `CLAIM_UNSUPPORTED` 拦截；无关成功工具也不支撑；FAILED/TIMEOUT 不算支撑；**连续 20 次构造幻觉，对外泄漏 = 0**。**端到端"泄漏=0"无法验证**（未接线）。 |
| AC-B14 数值可回溯放行 | **通过（组件级）** | `AntiHallucinationVerificationTest.traceableNumericPasses`。 |
| AC-B15 内容安全命中 + Fail-Closed | **通过** | `ContentSafetyFailClosedVerificationTest`（4 项）：词库不可用 + Fail-Closed=true → **不放行**；命中敏感词拦截、正常文本放行。 |
| AC-B16 LLM 超时兜底 | **无法验证** | 端到端未接线。 |
| AC-B17 工具失败回注不中断 | **无法验证** | 端到端未接线。 |

### 2.3 段 C：宠物档案真实工具

| AC | 结论 | 证据 / 缺失原因 |
| --- | --- | --- |
| AC-C1 登记落库 + 快照回复 | **部分通过** | 应用层 CRUD 通过（`PetProfileUniquenessVerificationTest`）；**落库与对话触发无法验证**（无 DB + D1）。 |
| AC-C2 缺 pet_name 追问 | **无法验证** | 工具未接线（D1）+ 无 DB。 |
| AC-C3 同名拦截 | **通过** | `PetProfileUniquenessVerificationTest.duplicateNameBlocked`（应用层 dedup，`PET_NAME_DUPLICATE`，无重复行）。 |
| AC-C4 改生日（前后值审计） | **部分通过** | 增量更新逻辑通过；**`log_audit` 落库无法验证**（无 DB）。 |
| AC-C5 年龄可回溯 | **无法验证** | 工具未接线（D1）。 |
| AC-C6 软删后重建同名 | **部分通过** | 应用层：`recreateAfterSoftDelete` 通过；DDL：`StaticArtifactVerificationTest.petUniqueUsesGeneratedColumn`（生成列 `live_marker` + 唯一键）通过。**真实 MySQL 唯一索引行为无法验证**（无 DB）。 |
| AC-C7 非法生日拒绝 | **通过** | `PetProfileUniquenessVerificationTest.futureBirthdayRejected`。 |
| AC-C8 多宠物歧义澄清 | **无法验证** | 工具未接线（D1）。 |
| AC-C9 跨用户不可见 | **通过（应用层）** | `PetProfileUniquenessVerificationTest.crossUserIsolation`（`listLive`/`findLiveByName` 按 openid）。注：对话侧入口未接线。 |

### 2.4 段 D：Mock/Real 切换

| AC | 结论 | 证据 / 缺失原因 |
| --- | --- | --- |
| **AC-D1** local+Mock 全链路走通、P95≤3s | **失败** | 即使补齐 DB，**主链路未接线（D1）→ 全链路不可能走通**；另无 DB。 |
| AC-D2 prod 占位符 Fail-Fast | **部分通过** | 静态：`application-prod.yml` `placeholder-fail-fast: true`、`PlaceholderConfigValidator` 存在。**启动 Fail-Fast 行为未独立实跑**（无 DB/未启动）。 |
| AC-D3 llm.provider 零代码切换 | **通过（静态审查）** | `LlmConfig` 以 `@ConditionalOnExpression("'${llm.provider:mock}'=='mock'")` 装配 Mock/Real 两个 `LlmClient` 实现；切配置即切换，无业务代码改动。 |
| AC-D4 `/api/doctor` 结构化体检 | **无法验证** | 需启动后端（依赖 DB）。 |

### 2.5 段 E：管理后台

| AC | 结论 | 证据 / 缺失原因 |
| --- | --- | --- |
| AC-E1 登录返回 JWT + 审计 | **部分通过** | JWT 签发/解析通过（`SecurityGateVerificationTest.validTokenAuthenticates`）；**账号校验与 `log_audit` 落库无法验证**（无 DB）。 |
| **AC-E2 无 Token → 401（非 200）** | **通过** | `SecurityGateVerificationTest`（入口点 401 + `code=20001`；无 Token 不建认证；非法/已注销 Token 不建认证）；`RbacSurfaceVerificationTest.publicEndpointsDoNotLeakAdminApis` 确证放行清单不含后台端点。 |
| AC-E3 OPERATOR 调配置写 → 403 | **通过（静态+处理器）** | `RbacSurfaceVerificationTest.configEndpointsRequireSuperAdmin`（仅 `hasRole('SUPER_ADMIN')`）+ `RestAccessDeniedHandler` 403（`SecurityGateVerificationTest.deniedHandlerReturns403`）。注：越权**未落审计**（D5）。 |
| **AC-E4 连续 5 次失败锁 15 分钟，第 6 次正确口令仍拒** | **通过** | `AuthLockoutWindowVerificationTest.sixthAttemptRejectedEvenWithCorrectPassword`（第 6 次 `ACCOUNT_LOCKED`=20004，`fail_count=5`、`locked_until∈(now, now+16min)`）；另验证锁定到期后可正常登录并归零、防账号枚举。 |
| AC-E5 看板与 SQL 逐项一致 | **无法验证** | 无 DB。静态：`DashboardService` 全部用 `selectCount` 聚合（与 SQL 一一对应，非估算）。 |
| AC-E6 工具日志检索 + 降级原因 | **无法验证** | 无 DB。静态：`ToolLogQueryService`/`ToolLogController` 就绪，详情含入参/结果/耗时/`fallback_reason`。 |
| AC-E7 traceId 全链路还原 | **无法验证** | 需运行期日志（需启动）。 |
| **AC-E8 敏感字段 0 泄漏** | **通过** | `MaskingVerificationTest`（5 项：openid 前4+`****`+后4、短标识全掩、SECRET 仅尾号、视图不含明文）；代码审查：日志处均用 `MaskUtils.openid`，MDC 仅写 `traceId`，无明文 openid 泄漏。 |
| **AC-E9 AUDITOR 只读可用 / 写 403** | **通过（静态）** | `RbacSurfaceVerificationTest`：读接口（用户/会话/工具日志/审计/看板）均含 `AUDITOR`；档案写仅 `SUPER_ADMIN/OPERATOR`、`AUDITOR` 不在其列。 |

### 2.6 段 F：工程门禁

| AC | 结论 | 证据 / 缺失原因 |
| --- | --- | --- |
| AC-F1 空库执行 DDL（8 表 + G-01~G-07） | **部分通过** | `StaticArtifactVerificationTest`：8 表齐备、均 `ENGINE=InnoDB`/`utf8mb4`、宠物表生成列方案正确。**真实迁移执行无法验证**（无 DB）。 |
| AC-F2 构建/单测/tsc/依赖扫描门禁 | **无法验证** | 未跑 CI；`.github/workflows/ci.yml` 存在（静态）。 |
| AC-F3 占位符 Fail-Fast | **部分通过** | 同 AC-D2（静态就绪，未独立实跑启动）。 |
| AC-F4 证据清单（每 P0 附证据，无证据不计完成） | **部分通过** | `docs/evidence/index.md` 存在；**但其对 AC-A7 的证据锚点与事实不符**（引用 `MainChainE2ETest`，而该测试不含 AC-A7 断言）——见 §4.4。 |
| AC-F5 技术栈引用点 | **部分通过** | 静态：pom 中各依赖有真实引用；ECharts 标"预留未启用"。未逐项核验。 |

### 2.7 性能类（AC-P1~P6）

| AC | 结论 | 缺失原因 |
| --- | --- | --- |
| AC-P1~P6（P95 时延、并发 50 路） | **无法验证** | 缺压测环境与运行实例（且无 DB）。 |

### 2.8 统计汇总

| 结论 | 条数 | 占比 |
| --- | --- | --- |
| **通过** | 19 | 31.7% |
| **部分通过** | 10 | 16.7% |
| **失败** | 3（AC-A7、AC-A8、AC-D1） | 5.0% |
| **无法验证** | 28 | 46.7% |
| 合计（AC 数） | 60 | 100% |

> "部分通过"= 该 AC 的可离线组件级子项通过，但端到端/落库子项因缺环境无法验证。
> 无法验证的 28 项中，段 B 占 14、段 C 占 3、段 E 占 3、性能占 6；绝大多数根因是 **D1（主链路未接线）+ 无 MySQL 凭据**，并非能力缺失。

---

## 3 独立发现的缺陷清单

| 编号 | 严重度 | 现象 | 复现方式 | 影响 | 建议归属 |
| --- | --- | --- | --- | --- | --- |
| **D1** | **阻断** | **主链路未接线**：`TextMessageHandler.handle()` 恒 `return null`，注释写"T04 将在此调用 AgentOrchestrator"但**从未实现**；全仓 `src/main` **无任何 `AgentOrchestrator.run(...)` 调用点**（仅测试引用）。文本消息经回调后仅回 `success`，**不产生业务回复** | `"$MVN" -B -f backend/pom.xml test` → `MessageRoutingVerificationTest.textMessageMustEnterDialogueEngine` **失败**；或 `grep -rn "AgentOrchestrator" src/main` 仅见其自身定义 | **MVP 一句话定义**（"微信消息→…→意图识别→工具编排→真实工具执行→…→回复"）**未达成**；AC-A7、B*、C*、D1 均连带不成立；反幻觉/宠物工具虽已实现但**运行期不可达** | Engineer |
| **D2** | **严重** | **AC-A8「先回执后推送」未实现**：`WechatMessageService.pushAsync` 为**死代码**（无调用点）；回调同步阻塞整条链路后才返回终态 | `AsyncReceiptContractVerificationTest` **失败**：链路 sleep 8s 时实测阻塞 **8024ms**，且 `transport.sendCustomerMessage` **从未调用** | 长链路将导致微信侧超时/用户体验差；AC-A8 不成立；`message.send_status`/异步推送链路形同虚设。注：PRD 4.1 将"客服消息异步推送"标为 *骨架*，但 AC-A8 给出了"≤1s 回执 + 异步终态"的可验收口径，故据验收口径判**失败** | Engineer |
| **D3** | 中 | **`log_tool_call.call_seq` 恒为 0**：`ToolCallLogServiceImpl.logStart` 硬编码 `setCallSeq(0)`，接口无序号入参，编排器也不传序号 | 阅读 `ToolCallLogServiceImpl` 第 51 行；`AgentOrchestratorImpl.executeOne` 调用处 | 无法体现工具编排顺序；**AC-B6（call_seq=1）、AC-B7（call_seq 递增）的证据无法成立** | Engineer |
| **D4** | 中 | **`wx_user` 表从不写入**：全仓仅 `selectPage/selectById/selectList/updateById`，**无任何 insert**；`last_interact_at` 亦无更新点 | `grep -rn "wxUserMapper" src/main` 无 insert；`WxUserEntity` 无写入 | 后台"用户列表"恒空、看板"活跃用户数"恒 0；FR-16 骨架（P1-05）与 AC-E5 的该指标无实际数据 | Engineer |
| **D5** | 低/中 | **越权未落审计**：`RestAccessDeniedHandler` 直接返回 403，**未写 `log_audit`** | 阅读 `RestAccessDeniedHandler` | AC-E3 要求"记录越权尝试"，审计链缺口（AUDITOR 诉求 US-12） | Engineer |
| **D6** | 低 | **LLM 异常归类过粗**：`AgentOrchestratorImpl` 将所有 `LlmException`（含 `LlmProtocolException`）统一按 `LLM_TIMEOUT` 降级 | 阅读 `run()` 第 163-166 行 | 与 SRS 9.5"输出格式非法→重解析一次"不一致（协议错误被当作超时） | Engineer |

> D1/D2 为**交付阻断项**；D3/D4 影响验收证据完整性；D5/D6 为质量改进项。

---

## 4 代码审查发现

### 4.1 权限矩阵（结论：**无未加保护的后台写端点**）
`RbacSurfaceVerificationTest.everyAdminWriteEndpointIsGuarded` 反射扫描全部后台控制器：凡非 `/api/auth`、`/api/wx` 前缀的 `POST/PUT/DELETE` **均声明了 `@PreAuthorize`**，未发现缺口。`ConfigController` 仅 `SUPER_ADMIN`；`PetController` 写为 `SUPER_ADMIN`/`OPERATOR`；`UserController.updateStatus/export` 仅 `SUPER_ADMIN`；`AuditLogController` 为 `SUPER_ADMIN`/`AUDITOR`。

### 4.2 明文密钥（结论：**未发现**）
`.env.example` 全为空值；`application*.yml` 一律 `${ENV:default}`；初始管理员用占位符 `__ENV_INJECTED__`（`StaticArtifactVerificationTest.adminSeedHasNoHardcodedPassword` 通过，无 BCrypt 明文哈希）。`application-local.yml` 的 `DB_PASSWORD:1234` 为本地开发默认值（可接受，建议注释强调不可用于生产）。

### 4.3 日志脱敏（结论：**未发现明文泄露**）
日志调用点均使用 `MaskUtils.openid(...)`；`TraceContext` 仅向 MDC 写 `traceId`；`logback-spring.xml` 虽声明 `includeMdcKeyName openid`，但**无写入点**（无害）。`SysConfig` 的 `SECRET` 值经 `MaskUtils.secret` 仅出尾号。

### 4.4 测试排除的诚实性（结论：**基本诚实，但 evidence/index 有夸大**）
- `pom.xml` 以 `<excludedGroups>integration</excludedGroups>` **显式排除**（非静默通过），并在注释中说明 CI 另跑——**良好实践**。
- ⚠️ **命名陷阱**：`*IT.java` 后缀**不被 surefire 默认拾取**。QA 最初的 `RedisLiveCacheVerificationIT` 因此被**静默漏跑**（150 vs 153 之差即为证据），已改名 `*Test` 后实跑通过。团队若新增 `*IT` 类需留意。
- ⚠️ `docs/evidence/index.md` 存在**自我陈述与事实偏差**：其"AC-A7 text 进入对话引擎"的证据锚点为"集成 `MainChainE2ETest`"，但该测试实际**不包含任何 AC-A7 断言**（仅测 401/403/登录/验签）；"AC-A8"标注为已覆盖，而实际未实现（D2）。**证据索引须按本报告修订**。

### 4.5 其它
- `SecurityConfig` 公开端点含 `/actuator/info`、`/actuator/prometheus`；`health.show-details` 在 local 为 `always`、prod 为 `never`。生产已收敛，本地暴露可接受，建议线上复核。
- `RedisDedupService`/`RedisRateLimitService` 均为 **Fail-Open**（Redis 不可用放行）——符合 BR-29"保护而非惩罚"，已如实注释。

---

## 5 无法验证项清单（缺什么 / 补什么条件即可验证）

| 项 | 缺什么 | 补什么即可验证 |
| --- | --- | --- |
| 全部"落库/SQL/看板/审计"断言（AC-A1 落库、A3 落库、C1/C4、E1/E5/E6） | MySQL 凭据 | 提供 `DB_PASSWORD`；或启动 Docker daemon 后 `docker compose up -d mysql` |
| Flyway 真实迁移（AC-F1） | 同上 | 同上（`mvn spring-boot:run` 触发迁移） |
| 端到端主链路与段 B/C（AC-A7/A8/B*/C*/D1） | **① 源码接线（D1）+ ② DB + ③ Redis** | ① Engineer 修复 D1（`TextMessageHandler` 注入并调用 `AgentOrchestrator`）② 提供 DB ③ Redis 已就绪；随后 `scripts/e2e-smoke.ps1` + 集成用例 |
| AC-D4 `/api/doctor`、AC-E7 traceId 链路、AC-F3 启动 Fail-Fast | 可启动的运行实例（需 DB） | 提供 DB 后以 `local`/`prod` profile 启动 |
| AC-F2 CI 门禁 | CI 运行环境 | 推送后查看 GitHub Actions，或本地按 CI 步骤执行 |
| AC-P1~P6 性能 | 压测环境与工具（如 wrk/JMeter） | 部署实例后抽样 20 次计时 / 50 路并发 |
| 真实微信/LLM/物流/地图外呼 | 外部凭据（AS-01/02/03） | 超出 MVP 范围（Mock 通道），切换需真实凭据与备案域名 |

---

## 6 质量评估与残留风险

**组件级质量：较高。** 验签、MsgId 幂等（真实 Redis 实证）、限流（真实 Redis 实证）、脱敏、内容安全 Fail-Closed、执行一致性校验、上下文 tool/assistant 配对不变式、RBAC 表面、DDL 生成列方案——均取得**独立实证且通过**。反幻觉（本项目核心差异化）算法实现**正确**。

**系统级质量：不达标。** 因 **D1**，已实现的对话引擎/工具/反幻觉在**运行期不可达**；**D2** 使"先回执后推送"承诺落空。即"零件合格，但整机未组装"。

**残留风险：**
1. 一致性校验**只在编排器内**，未接线则等于不存在——核心差异化能力实际未生效（风险等级：高）。
2. 词库为 6 条**占位词**，上线前须替换为合规词库（`safety/wordlist.txt` 注释已声明）。
3. SRS 9.4.5 步骤 3 的"**实体**可回溯"未实现（仅数值回溯）——事实性幻觉中非数值实体不受约束。
4. 数值提取为粗粒度正则（日期被拆为 `2020/05/01` 三段），回溯判断偏宽松（当前用例仍正确）。
5. `wx_user` 无写入 → 后台用户与活跃度指标无真实数据（D4）。

---

## 7 最终判定

> **MVP 未达到"可交付"标准。**

**阻断项（必须修复后方可复验）：**
1. **D1 — 主链路未接线**：文本消息未进入对话引擎，端到端主链路不可用（违反 MVP 一句话定义；AC-A7/AC-D1 失败）。
2. **D2 — AC-A8 未实现**：回调同步阻塞、无异步推送（AC-A8 失败）。

**建议一并修复（非阻断）：** D3（`call_seq`）、D4（`wx_user` 写入）、D5（越权审计）、D6（异常归类）。

**复验条件：** 修复 D1/D2 + 提供 MySQL 凭据（或 Docker），重跑 `mvn test` 全绿并执行 `scripts/e2e-smoke.ps1`；本报告的"无法验证"项即可转为"通过/失败"。

---

## 附录 A：QA 验证测试清单（`backend/src/test/java/com/lumensteward/clawbot/verification/`）

| 测试类 | 用例数 | 结果 | 覆盖 |
| --- | --- | --- | --- |
| `WechatSignatureVerificationTest` | 6 | 通过 | AC-A1/A2/A5 |
| `WechatEchoVerificationTest` | 2 | 通过 | AC-A4 |
| `AntiHallucinationVerificationTest` | 7 | 通过 | AC-B13/B14、9.4.5 |
| `ContentSafetyFailClosedVerificationTest` | 4 | 通过 | AC-B15、BR-12 |
| `ContextPairingInvariantVerificationTest` | 4 | 通过 | 9.4.4 配对不变式 |
| `PetProfileUniquenessVerificationTest` | 6 | 通过 | AC-C3/C6/C7/C9、BR-03/07 |
| `MaskingVerificationTest` | 5 | 通过 | AC-E8、BR-21 |
| `SecurityGateVerificationTest` | 7 | 通过 | AC-E2/E3、G-08 |
| `RbacSurfaceVerificationTest` | 5 | 通过 | AC-E3/E9、权限缺口扫描 |
| `AuthLockoutWindowVerificationTest` | 3 | 通过 | AC-E4 |
| `StaticArtifactVerificationTest` | 4 | 通过 | AC-F1、AC-C6、BR-20 |
| `RedisLiveCacheVerificationTest`（真实 Redis） | 3 | 通过 | AC-A3、BR-29 |
| `MessageRoutingVerificationTest` | 5 | **1 失败** | AC-A6/A9/A10；**D1 反证** |
| `AsyncReceiptContractVerificationTest` | 1 | **1 失败** | **D2 反证** |
| **合计** | **62** | 60 通过 / 2 失败 | — |

## 附录 B：复现命令

```bash
MVN="/c/Users/ASUS/.m2/wrapper/dists/apache-maven-3.9.15-bin/4rlcemksed9vjmkvgss0jpc4po/apache-maven-3.9.15/bin/mvn.cmd"   # 注意：勿用 unix mvn
"$MVN" -B -f backend/pom.xml test            # 全部单测（含 QA 验证，排除 @Tag("integration")）
"$MVN" -B -f backend/pom.xml test -Dgroups=redis-live   # 仅真实 Redis 用例
# 报告：backend/target/surefire-reports/
```

---

## 8 第 2 轮复验（Round 2 Re-verification）

> 本轮为**回归复验**：针对第 1 轮报告缺陷 D1~D6（工程师已修复）逐项复验，并复核「我的测试是否被削弱」「evidence 索引更正是否到位」。**第 1 轮结论（§1–§7、附录 A/B）全部保留，未删除**，以保证可追溯。

### 8.1 复验环境的新变化（重要）

第 1 轮时 Docker daemon 未运行；本轮复验前探测发现 **Docker Desktop 已运行**（Linux 容器，20 vCPU），据此我**额外取得一批"真实数据库 / 真实端到端"实证**——第 1 轮标注"无法验证"的多数项因此可转为"通过/失败"。

| 能力 | 第 1 轮 | 第 2 轮实测 |
| --- | --- | --- |
| Docker | 未运行 | **运行**（Docker Desktop 29.8；`mysql:8.4` 可拉起） |
| MySQL | 无凭据 | **可用**：`docker compose -p clawbotqa up -d mysql`（宿主 13306）→ 后端 `local` 连库成功 |
| Redis | 可用 | 可用（后端 Redisson 连接成功） |
| 真实端到端 | 未取得 | **已取得**：jar 启动 + 实时 HTTP 走查 + 真实库行核对（见 §8.5） |

> **过程透明说明**：为执行集成/端到端验证，我**临时**注释掉 `backend/pom.xml` 的 `<excludedGroups>integration</excludedGroups>`（**已还原**，`git status` 证实 pom 未被改动），并**临时**将 `~/.testcontainers.properties` 指向 Docker Desktop 管道（**已还原**）。业务源码、SRS、PRD、架构文档**均未改动**；本次改动仅限我的测试代码与本报告。

### 8.2 D1~D6 逐项复验结论

| 缺陷 | 复验结论 | 证据 |
| --- | --- | --- |
| **D1 主链路接线** | **通过** | ① `TextMessageHandler` 构造器注入 `AgentOrchestrator`（`TextMessageHandler.java:49`），`handle()` 第 82 行 `orchestrator.run(request)`；② **真贯通测试** `MainChainWiringVerificationTest`（评估见 §8.3）；③ **实时端到端**：`POST /api/wx/callback`（真实签名）返回真实业务回复 `（Mock）我在呢，随时听你说～`；后端日志呈完整链路 `WechatCallbackController → MockLlmClient.chat → AgentOrchestratorImpl[编排完成 rounds=1 tools=1] → TextMessageHandler[经对话引擎产出回复]` |
| **D2 先回执后推送** | **通过** | `WechatCallbackController.RECEIPT_GRACE_MS=700` + **有界**线程池（`ArrayBlockingQueue(256)` + `AbortPolicy`，L107）+ `future.get(700ms)` 超时后 `pushAsync(占位)` 并 `future.thenAccept(pushFinal)`；**强化版** `AsyncReceiptContractVerificationTest`（链路 sleep 8s）：**回调 ≤1000ms 返回占位**、终态经客服消息**异步**推送、`assistant` 行 `send_status=1`。该用例实测耗时 **8.43s**，证明其**真正等待了慢链路**而非空跑 |
| **D3 `call_seq`** | **仍未通过（失败·反证）** | 代码层已传序号（`AgentOrchestratorImpl.executeOne(..., callSeq[0]+1, ...)` → `logStart(...,callSeq)`），**但运行期无效**：实时库 `log_tool_call` **恒为空**，INSERT 报 `Data too long for column 'trace_id'`（**新缺陷 D7**，见 §8.6）→ AC-B6/B7 仍不成立 |
| **D4 `wx_user` upsert** | **通过** | `WechatMessageService.upsertUser`（首交互 insert / 已存在刷新）；**实时库** `wx_user` 含 `qa_openid_a1`、`qa_openid_a3`、`qa_openid_c1` 各 1 行 |
| **D5 越权落审计** | **代码级通过；端到端无法验证** | `RestAccessDeniedHandler` 403 时同步 `auditLogService.record(reg_type=AUTH, action=ACCESS_DENIED, result=0)`（best-effort）；403 语义单测通过。**"越权真实落 `log_audit`"未取得实证**——缺 OPERATOR 账号令牌（与第 1 轮一致）。注：实时库 `log_audit` 有 2 行，均为**登录成功**审计（AC-E1），非越权 |
| **D6 LLM 异常归类** | **通过（代码级）** | `AgentOrchestratorImpl.mapLlmReason`：`LLM_TIMEOUT→LLM_TIMEOUT`、`LLM_INVALID_OUTPUT→LLM_INVALID_OUTPUT`、`LLM_QUOTA_EXCEEDED→BUDGET_EXCEEDED`、其余→`LLM_UNAVAILABLE`；`FallbackReason` 已含对应枚举。无独立运行期注入实证（Mock 通道不产生这些异常） |

### 8.3 新增测试 `MainChainWiringVerificationTest` 的有效性评估

**结论：构成"真贯通"断言，未绕过接线。** 依据：
- 它**未**重新 `new TextMessageHandler()` 后单独断言，也**未** mock 分发环节；而是装配 `真实 WechatCallbackController`（真实签名校验器 + 真实报文解析器）+ `真实 MessageDispatcher` + `真实 TextMessageHandler`（注入真实 `SessionResolver` 与"可记录调用"的 `AgentOrchestrator` 替身），以**真实签名** POST 触发。
- 断言：编排器 `run()` **确被调用**且入参 `openid/userMessage/sessionId` 与入站一致；`wx_message` 落 **user + assistant** 两行、assistant == 编排器终态且 `send_status=1`；首交互 `wx_user` insert 被调用。
- 唯一的替身是**被测边界之外**的 `AgentOrchestrator`（`RecordingOrchestrator` 仅记录调用），属**接口级**替换而非绕过——**足以证伪"未接线"**。真实编排器（含 LLM/工具/降级）由 §8.5 的**实时端到端**另行覆盖。
- 佐证：实时日志确实出现 `TextMessageHandler → AgentOrchestratorImpl` 调用链，与断言一致。

> 对比：我第 1 轮的 `MessageRoutingVerificationTest#textMessageMustEnterDialogueEngine` 用**无参构造** `new TextMessageHandler()`，其"非空"断言在修复后由**兜底文案**满足——**不足以证明接线**。本轮我已将其**强化**为"注入式委派"断言（见 §8.4）。

### 8.4 我的原失败用例：当前状态 + 是否被削弱

| 用例 | 第 1 轮 | 第 2 轮 | 是否被削弱 |
| --- | --- | --- | --- |
| `AsyncReceiptContractVerificationTest#shouldReturnEarlyReceiptAndPushAsync` | 失败（阻塞 8024ms） | **通过** | **未被削弱**：`git hash-object` 与提交 `4a34bd9` 的 blob **完全一致**（`59cf7b80…`）；本轮我又**追加**断言（终态异步推送 + `send_status=1`），实测 8.43s |
| `MessageRoutingVerificationTest#textMessageMustEnterDialogueEngine` | 失败（返回 null） | **通过** | 断言**未被削弱**（仍断言"须产出业务回复"，未被改成迁就实现的弱断言）；但修复后该方法会由**兜底文案"假绿"**，故本轮我**强化**为"注入编排器 → 断言 `run()` 被调用且回复 == 编排器终态"（真接线断言） |

> 工程师对其自有测试 `AgentLoopConstraintTest` 的改动（`4a34bd9`）为 **D3 接口签名适配**（`logStart` 增 `callSeq` 参），**非削弱**，属合理同步。

### 8.5 实时端到端实跑证据（本轮新增，真实 MySQL 8.4 + Redis）

命令：`docker compose -p clawbotqa up -d mysql`（宿主 13306）→ `java -jar backend/target/clawbot-backend.jar`（`local` + Mock）。启动日志：Hikari 连库成功 → **Flyway 应用 5 个迁移至 v1.0.4** → Redisson 连 Redis → `ToolRegistry names=[manage_pet_profile]` → `Started ClawbotApplication`。

| AC | 结论 | 实时实证 |
| --- | --- | --- |
| AC-A1 | **通过** | POST 真实签名文本 → 200 + 业务回复 `（Mock）我在呢…`；库 `wx_message` 落 user+assistant |
| AC-A2 | **通过** | 伪造签名 100 次 → **100/100 拒绝**（全部 `code=20003`，业务处理 = 0） |
| AC-A3 | **通过** | 同 MsgId ×10 → 库中该 openid **user 行 = 1**（首条处理后 9 条日志 `重复消息幂等丢弃`） |
| AC-A4 | **通过** | GET 合法签名 → 200 + `echostr` 原样回显 |
| AC-A7 | **通过** | 见 D1 |
| AC-C1 | **通过** | 对话"登记猫" → Mock LLM `tool_calls=1` → `manage_pet_profile` **真实写库**（`biz_pet_profile` 有行）；`wx_user` upsert |
| AC-C3 | **通过（真实 MySQL）** | 重复活体同名 INSERT → `ERROR 1062 … uk_openid_pet_name_live_marker` |
| AC-C6 | **通过（真实 MySQL）** | 软删后重建同名 → 成功，活体行 = 1、总行 = 2 |
| AC-E1 | **通过** | `POST /api/auth/login` superadmin → 200 + JWT（`role=SUPER_ADMIN`）；登录审计落库 |
| AC-E2 | **通过** | 无 Token GET `/api/users` → **401** |
| AC-E5 | **通过** | `GET /api/dashboard/summary` = `{todayMessages:6, activeUsers:3, toolCalls:0}` 与直接 SQL **逐项一致** |
| AC-F1 | **通过** | Flyway 在真实 MySQL 8.4 上应用 5 迁移、8 表建立 |

> Mock 说明：Mock LLM 对"登记猫"返回**固定文案**（含"咪咪"），与入参"豆豆"不同——属 **Mock 脚本行为，非幻觉、非缺陷**。

#### 8.5.1 集成用例（`@Tag("integration")`）仍不可执行的原因
`MySqlMigrationTest` / `MainChainE2ETest` / `MainChainRowEvidenceTest` 依赖 Testcontainers；其 Java npipe 客户端在本机**连到 Docker Desktop 的 `dockerDesktopLinuxEngine` 管道后，`/info` 返回 HTTP 400**（Docker 29.8 与所用 Testcontainers npipe 策略不兼容）。故 **Testcontainers 路径不可执行**；我改用 `docker compose + jar + 实时 HTTP + 直连 SQL` 取得**等价甚至更强**的实证（§8.5）。**这些集成用例仍不得计为"通过"。**

### 8.6 本轮新发现缺陷

| 编号 | 严重度 | 现象 | 复现 | 影响 | 归属 |
| --- | --- | --- | --- | --- | --- |
| **D7** | **高** | **工具调用日志在真实库恒写入失败**：`V1.0.2__baseline_biz.sql` 定义 `log_tool_call.trace_id VARCHAR(32) NOT NULL`，而运行期写入的 traceId 为 **36 位 UUID** → `INSERT` 报 `MysqlDataTruncation: Data too long for column 'trace_id'`，被 `ToolCallLogServiceImpl` 的 best-effort 分支**静默吞掉**（仅 WARN） | 起真实库跑一次触发工具的对话 → 后端日志报上述错误；`SELECT COUNT(*) FROM log_tool_call` = **0**（工具确已执行、宠物确已落库，日志却 0 行） | ① **AC-B6/B7（`call_seq`）证据恒不可得**（D3 修复运行期无效）；② AC-E6 工具日志检索无数据；③ ADR-003"同步落库"实际未生效；④ 工具可观测性/排障链断裂。**单测与组件级测试均无法发现**（不落真实库） | Engineer |

> 附带观察（非独立缺陷）：`log_tool_call.session_id` 亦为 `NOT NULL`；若会话解析失败需关注（本次因 trace_id 先失败未暴露）。

### 8.7 更新后的验证统计

| 结论 | 第 1 轮 | 第 2 轮 | 变化说明 |
| --- | --- | --- | --- |
| **通过** | 19 | **27** | +A1、A7、A8、C1、C6、E1、E5、F1（真实库/端到端实证） |
| **部分通过** | 10 | **6** | A1/C1/C6/E1/F1 升为通过；D1 由失败升为部分（全链路走通已证，P95 未压测） |
| **失败** | 3 | **2** | A7/A8 转通过、D1 转部分；**新增 B6/B7 与 E6（D7 反证：工具日志恒空）** |
| **无法验证** | 28 | **25** | E5 转通过、B6/B7 转失败；其余（段 B、C4、E7、F2/F3、P*、真实外呼）仍受环境/凭据限制 |
| 合计（AC） | 60 | 60 | — |

> 复验命令：`"$MVN" -B -f backend/pom.xml test` → **Tests run: 159, Failures: 0, Errors: 0, BUILD SUCCESS**（工程师原 96 + 我的 14 类共 63 用例；含真实 Redis 3 条）。

### 8.8 最终判定

> **D1、D2 已修复并取得实证；D4/D5/D6 修复到位；D3 代码层修复但运行期无效。本轮新发现 D7（工具日志恒失败），导致 AC-B6/B7/E6 失败。**

**MVP 仍判定为「未达可交付」**，剩余阻断/高优先项：
1. **D7（高，阻断证据完整性）**：`log_tool_call.trace_id` 列宽 32 < UUID 36 → 扩列（如 `VARCHAR(64)`）并加迁移；修复后 AC-B6/B7（`call_seq`）、AC-E6 方可复验。**建议同时核查其余 `trace_id/长标识` 列宽**。
2. **D3 复核**：D7 修复后重验 `call_seq=1` 递增。

**已闭环**：D1（主链路）、D2（先回执后推送）、D4（用户 upsert）；D5 待 OPERATOR 令牌做端到端复核；D6 代码级通过。

**复验条件**：修复 D7 后，按 §8.5 方法重跑实时端到端，即可将 B6/B7/E6 转为通过/失败；提供 OPERATOR 令牌可闭环 D5/E3。
