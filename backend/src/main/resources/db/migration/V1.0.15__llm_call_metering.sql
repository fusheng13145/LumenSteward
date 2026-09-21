-- ===========================================================================
-- 表 log_llm_call（LLM 调用 token 计量明细，B-4 / FR-20 ③ / FR-17，迭代 4 W5）
--
-- 背景：手册 §7.7 把「LlmClient token 计量落地」列为 B-4 的前置条件，但实际前置**并未成立**：
-- token 数只进 Redis 计数桶（cost:llm:daily:* TTL 到次日零点、cost:llm:hourly:* TTL 2 小时），
-- **明细从未落库**。后果是①看板只能显示「今日剩余比例」，无法出趋势/按模型/按用途的分布；
-- ②Redis 一旦被清或换实例，历史成本永久归零、不可复核（违背 FR-17「可检索」与
-- FR-19「删除须可证明」所要求的持久事实底座）；③Mock provider 恒返回 0 token，
-- 本机连「计量在转」都无法取证。本表把「每次调用一行」变成可查询事实，看板才有数据面。
--
-- 设计边界：
--   1) 只增不改（无 updated_at / deleted_at），纯事实流水，不参与任何业务判定；
--   2) openid 仅存**脱敏形态**（BR-21），且**可空**：意图分类等调用发生在任务归属之前，
--      与其存一个假的归属，不如留 NULL 并在看板单列「未归属」（口径如实）；
--   3) total_tokens 由 prompt+completion 推导（与 Redis 计数同源同算法），
--      provider 若回报不同的 total 也不采用——两条口径必须可互相核对；
--   4) 高流量表，按 created_at 建索引并纳入 FR-19 定时清理（见 DataRetentionService）；
--   5) 索引使用独立 CREATE INDEX 语句（MySQL 8.4 兼容写法，避免解析报错）。
-- ===========================================================================
CREATE TABLE log_llm_call (
  id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  purpose           VARCHAR(20)  NOT NULL               COMMENT '调用用途:CHAT对话编排 MEMORY_EXTRACT状态库抽取 INTENT意图分类',
  provider          VARCHAR(32)  NOT NULL               COMMENT '供应商标识:mock/openai-compatible(取自 LlmClient.provider())',
  model             VARCHAR(64)  NULL                   COMMENT '模型名;请求未显式指定(用供应商默认)时为 NULL',
  openid            VARCHAR(64)  NULL                   COMMENT '脱敏后的用户标识(如 oabcd****wxyz);无用户归属的调用为 NULL',
  session_id        BIGINT       NULL                   COMMENT '会话 id(逻辑外键 -> wx_session.id,可空)',
  trace_id          VARCHAR(64)  NULL                   COMMENT '链路标识(与 X-Trace-Id 同源,下钻用)',
  prompt_tokens     INT          NOT NULL DEFAULT 0     COMMENT '输入 token',
  completion_tokens INT          NOT NULL DEFAULT 0     COMMENT '输出 token',
  total_tokens      INT          NOT NULL DEFAULT 0     COMMENT '合计 token(=输入+输出,与 Redis 日预算计数同口径)',
  created_at        DATETIME(3)  NOT NULL               COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='LLM 调用 token 计量明细(B-4 成本看板数据面)';

-- 留存清理 + 按日趋势主路径
CREATE INDEX idx_llm_call_created_at
  ON log_llm_call (created_at);

-- 按用户看消耗（top-users 排行）
CREATE INDEX idx_llm_call_openid_created
  ON log_llm_call (openid, created_at);

-- 按用途 / 按模型分布
CREATE INDEX idx_llm_call_purpose_created
  ON log_llm_call (purpose, created_at);

CREATE INDEX idx_llm_call_model_created
  ON log_llm_call (model, created_at);
