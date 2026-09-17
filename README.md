# LumenSteward · 衔光管家

> 微信 Claw 助手（LumenSteward）MVP · 分层单体（Modular Monolith，ADR-001）

前后端分离 + 分层单体。后端严格遵循 SRS 9.2 五层包结构
`interfaces → application → domain → infrastructure → common`，依赖方向自上而下单向。

- 需求基线：`微信Claw助手需求规格说明书.md`（SRS-CLAWBOT-002 V2.2，只读）
- 产品需求：`docs/MVP产品需求文档.md`（PRD）
- 架构设计与任务分解：`docs/MVP架构设计与任务分解.md`

---

## 1 目录结构

```text
.
├── backend/                 # Spring Boot 3 后端（JDK 17 目标字节码）
│   ├── src/main/java/com/lumensteward/clawbot/
│   │   ├── common/          # 统一响应 / 错误码 / 异常 / 枚举 / 工具
│   │   ├── infrastructure/  # 配置类 / 可观测性（traceId）/ 持久化 / 缓存 / 客户端 / 安全
│   │   ├── interfaces/      # Controller（微信回调 + 后台）/ DTO / Assembler（脱敏）
│   │   ├── application/     # 用例编排（对话引擎 / 调度 / 认证 / 后台查询 / 兜底）
│   │   └── domain/          # 领域规则与端口（工具 / 档案服务 / 上下文 / 意图）
│   └── src/main/resources/  # application*.yml / logback-spring.xml / db/migration / mock
├── frontend/                # Vue 3 + TS + Vite 管理后台
│   └── src/                 # router / config / stores / utils / services / directives / layouts / views
├── scripts/                 # e2e-smoke.ps1 端到端走查脚本
├── docs/                    # PRD / 架构设计 / 验收证据索引（evidence/index.md）
├── .github/workflows/ci.yml # CI 门禁（构建 / 单测 / 类型检查 / 依赖扫描）
├── docker-compose.yml       # MySQL 8 + Redis 7 + 后端 + 前端
└── .env.example             # 后端环境变量契约（真实 .env 不入库）
```

---

## 2 技术栈与状态

> 依 G-32/G-33：**未启用项一律显式标注「预留未启用」，不表述为已实现**。

### 2.1 后端（`backend/pom.xml`，Spring Boot 3.3.4 / JDK 17）

| 依赖 | 版本 | 用途 | 状态 |
| --- | --- | --- | --- |
| spring-boot-starter-web | 3.3.4 | 微信回调 + 后台 REST | 启用 |
| spring-boot-starter-validation | 3.3.4 | Jakarta Validation | 启用 |
| spring-boot-starter-security | 3.3.4 | JWT 过滤器链 + RBAC | 启用 |
| spring-boot-starter-data-redis | 3.3.4 | 上下文 / 去重 / 限流 / 黑名单 | 启用 |
| spring-boot-starter-actuator | 3.3.4 | health / metrics / prometheus | 启用 |
| micrometer-registry-prometheus | 由父 POM 管理 | `/actuator/prometheus` 指标 | 启用 |
| mybatis-plus-spring-boot3-starter | 3.5.7 | 单表 CRUD + 分页 + 逻辑删除 + 审计填充 | 启用 |
| mysql-connector-j | 8.4.0 | MySQL 8 驱动 | 启用 |
| flyway-core / flyway-mysql | 10.17.0 | 版本化增量迁移 | 启用 |
| redisson-spring-boot-starter | 3.32.0 | 分布式锁 + 限流 | 启用 |
| jjwt（api/impl/jackson） | 0.12.6 | JWT 签发与校验 | 启用 |
| resilience4j-spring-boot3 | 2.2.0 | 超时 / 熔断 / 重试 | 启用 |
| springdoc-openapi-starter-webmvc-ui | 2.6.0 | OpenAPI 3（仅非生产开放） | 启用 |
| logstash-logback-encoder | 7.4 | 结构化 JSON 日志 | 启用 |
| caffeine | 3.1.8 | 本地一级缓存 | 启用 |
| lombok | 1.18.34 | 样板代码 | 启用（编译期） |
| spring-boot-starter-test / spring-security-test | 3.3.4 | JUnit5 + Mockito + AssertJ | 启用（test） |
| testcontainers（junit-jupiter/mysql） | 1.20.3 | 集成测试基座 | 启用（test） |
| dependency-check-maven | 10.0.4 | 依赖漏洞扫描 | 启用（CI 显式调用） |
| shedlock-spring | 5.16.0 | 定时任务分布式锁 | **预留未启用**（迭代 3） |
| spring-boot-starter-amqp | 3.3.4 | RabbitMQ 长任务解耦 | **预留未启用** |
| spring-boot-starter-webflux | 3.3.4 | SSE 实时通道 | **预留未启用**（迭代 3） |
| h2 | 2.2.224 | 内嵌开发/测试库 | **预留未启用** |

### 2.2 前端（`frontend/package.json`，Vue 3 + TS + Vite）

| 依赖 | 版本 | 用途 | 状态 |
| --- | --- | --- | --- |
| vue / vue-router / pinia | ^3.4 / ^4.4 / ^2.2 | 框架 / 路由 / 状态管理 | 启用 |
| element-plus | ^2.8.1 | 后台组件库 | 启用 |
| axios | ^1.7.7 | HTTP 单例封装 | 启用 |
| dayjs | ^1.11.13 | 时间格式化 | 启用 |
| vite / @vitejs/plugin-vue | ^5.4 / ^5.1 | 构建工具 | 启用 |
| typescript（`strict: true`）/ vue-tsc | ^5.5 / ^2.1 | 类型安全 / `tsc --noEmit` 门禁 | 启用 |
| eslint / prettier | ^9 / ^3.3 | 代码规范 | 启用 |
| vitest / @vue/test-utils | ^2.0 / ^2.4 | 前端单测 | 启用 |
| echarts | ^5.5.1 | 统计图表 | **预留未启用**（PRD N-8 / Q11） |

---

## 3 本地运行

### 3.1 依赖服务（前置）

- **MySQL 8**（3306）与 **Redis 7**（6379，无密码）必须先就绪；数据库 `clawbot` 由 Flyway 迁移自动建表。
- 数据库口令经 `DB_PASSWORD` 注入；`local` profile 缺省回落到 `1234`（仅本地）。
  ⚠️ **生成列唯一约束 `uk_openid_pet_name_live_marker` 依赖真实 MySQL**，请勿改用 H2。

```bash
# 方式一：仅起中间件（推荐，容器口令默认与 local 一致为 1234）
docker compose up -d mysql redis
# 方式二：整体编排（含前后端镜像）
docker compose up -d --build
```

### 3.2 环境变量（`.env.example` 为契约，见第 4 节）

| 变量 | 说明 | local 缺省 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 激活 profile | `local` |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | MySQL 连接 | `localhost` / `3306` / `clawbot` |
| `DB_USERNAME` / `DB_PASSWORD` | MySQL 凭据（**生产必改**） | `root` / `1234` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | Redis 连接 | `localhost` / `6379` / 空 |
| `WX_TOKEN` | 微信平台校验令牌（验签用，Mock 亦真实执行） | 占位（local 仅 WARN） |
| `WX_MOCK_ENABLED` | 微信通道 Mock 开关 | `true` |
| `LLM_PROVIDER` | LLM 通道 `mock` / `real` | `mock` |
| `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | LLM 网关（`real` 时必填） | 空 |
| `ADMIN_INIT_PASSWORD` | 初始 `SUPER_ADMIN` 口令（首次启动写入） | 空 |

### 3.3 后端

```bash
cd backend
# 本地开发（Mock 全开；占位符仅告警）——默认激活 local profile
./mvnw spring-boot:run        # 或使用本机 Maven：mvn spring-boot:run
# 编译与单测（含集成测试需 Docker：-Dgroups=integration）
mvn -B clean verify
```

> 启动后：`/actuator/health` 健康检查；`/swagger-ui.html` 接口文档（仅非生产开放）。

### 3.4 前端

```bash
cd frontend
npm ci
npm run dev          # 开发服务器 http://localhost:5173（/api 代理到 8080）
npm run type-check   # vue-tsc --noEmit（CI 门禁）
npm run build        # 生产构建 → dist/
```

### 3.5 端到端走查（一条命令）

后端以 `local` + Mock 启动后：

```powershell
pwsh -File scripts/e2e-smoke.ps1 -WxToken clawbot-local-token -AdminPassword <ADMIN_INIT_PASSWORD>
```

覆盖 AC-A1/A2/A3/A4、AC-C1、AC-E1~E3；脚本**自带微信签名生成器**，无需外部工具。

---

## 4 配置与密钥约定（BR-20 / 8.7）

- 密钥（`llm.api-key`、`wx.token`、`wx.encoding-aes-key`、DB 口令等）**一律环境变量注入**，
  不入库、不入 Git；`.env.example` 为配置契约，真实 `.env` / `.env.local` 已被 `.gitignore` 忽略。
- Mock/Real 开关：`llm.provider`（`mock`/`real`）、`wx.mock.enabled`（`true`/`false`），
  切换**零代码改动**（AC-D3）。
- `local` profile 下关键配置为占位符仅输出显著 WARN；`prod` profile 下**缺失/占位即启动失败**
  （Fail-Fast，AC-F2 / AC-F3）。

---

## 5 工程门禁（CI，`.github/workflows/ci.yml`）

| 作业 | 内容 | 固定版本 |
| --- | --- | --- |
| 后端 · 构建 + 单测 | `mvn clean verify` | Java 17 / Maven 3.9 |
| 前端 · 类型检查 + 构建 | `vue-tsc --noEmit` + `vite build` | Node 20 |
| 依赖漏洞扫描 | `dependency-check-maven:check`（CVSS ≥ 7 阻断）+ `npm audit --audit-level=high` | Java 17 / Node 20 |

四个环节（构建 / 单测 / 类型检查 / 依赖扫描）**任一失败即阻断合并**（AC-F2）。

---

## 6 本期实现范围说明

按架构文档 §7 任务分解推进，MVP 全量任务（T01~T05）已完成编码：

- **T01 工程骨架与门禁**：统一契约层（`ApiResponse` / `ErrorCode` / `PageResult`）、全局异常、
  `traceId` 横切、MyBatis-Plus 分页与审计填充、Redis/HTTP/OpenAPI 配置、前端骨架与 axios 三段式、CI、容器编排。
- **T02 数据层**：DDL + Flyway 增量迁移（含 `uk_openid_pet_name_live_marker` 生成列）、实体/Mapper、启动自检。
- **T03 接入层与 SPI/Mock**：微信单一回调（验签 + ±300s 窗口 + MsgId 幂等 + XXE 安全解析）、类型路由、
  SPI 契约与**可编程 Mock**（LLM/物流/地图/TTS）、`ToolRegistry` 启动 Schema 校验 Fail-Fast。
- **T04 对话引擎与输出治理**：Agent Loop（SC-01~SC-05 硬约束 + 强制收敛）、上下文裁剪（保持 tool/assistant 配对）、
  执行一致性校验（防幻觉，整条 DETECTED）、内容安全本地词库 **Fail-Closed**、兜底矩阵、真实工具 `manage_pet_profile`。
- **T05 管理后台与前端**：JWT + RBAC 三角色（401/403 严格）、登录失败锁定、登出黑名单、看板/工具日志/只读页、
  统一脱敏 `MaskingAssembler`、前端路由守卫/权限指令/Pinia、端到端走查脚本、验收证据索引。

**未启用技术栈**（ECharts、ShedLock、AMQP、WebFlux、H2）见第 2 节，均为**预留未启用**。

**验收证据**：见 `docs/evidence/index.md`（按 G-31 逐条列出证据类型与获取方式，未取得证据项如实标注）。
