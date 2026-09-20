-- ===========================================================================
-- V1.0.11__memory_item.sql
-- 数据域：业务域（biz_）—— 个人状态库（迭代 4 W6，SRS 7.6.1 分域 / BR-07 用户隔离）
--
-- 背景：§2.19 要求「跨会话持续生长的个人知识（人/地/物/偏好/惯例），带溯源」，
--       且必须是**生长式**（从微信活动自动抽取），不是人工灌库式 RAG（§7.7 明确不做后者）。
--       现有表只承载单次会话（wx_message / wx_session）与档案（biz_pet_profile），
--       没有任何跨会话事实存储，故管家「记不住说过什么」。
--
-- 设计边界：
--   1) **一条事实一行**，按 (openid, kind, name) 唯一（活记录内）：新事实覆盖旧事实，
--      旧行转 status=SUPERSEDED 保留（可溯源、可回看，不静默丢历史）；
--   2) 溯源三列：origin（来源方式）+ source_session_id / source_trace_id（哪段对话、哪条链路），
--      extractor 记抽取器标识与版本，使「谁写下的这条记忆」可核查。
--      刻意不存微信 MsgId：编排链路入参只有 sessionId/traceId，MsgId 不下沉到该层，
--      存一个恒为 NULL 的溯源列不如存两列真正可 JOIN 的（session_id → wx_message，
--      trace_id → log_orchestration_trace），二者合起来即可回看「原话 + 当轮决策」；
--   3) 承诺（commitment）状态机**不在本表**：其闭环依赖主动触达通道（W7/W8 阻塞项），
--      本表 kind 暂不引入 COMMITMENT，避免只存不动的空头承诺（见手册 §2.19 / §7.9 口径）；
--   4) 唯一约束沿用 V1.0.2 的生成列方案：live_marker 只对「未删除且 ACTIVE」取 1，
--      已覆盖行取 NULL，因此同名事实可保留多条历史而活记录严格唯一；
--   5) 索引使用独立 CREATE INDEX 语句（与 V1.0.10 同一实测可用写法）。
-- ===========================================================================
CREATE TABLE biz_memory_item (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  openid          VARCHAR(64)     NOT NULL                COMMENT '所属用户(隔离键,BR-07 禁止跨用户读取)',
  kind            VARCHAR(16)     NOT NULL                COMMENT '条目类型:PERSON人 PLACE地 THING物 PREFERENCE偏好 HABIT惯例 FACT事实',
  name            VARCHAR(64)     NOT NULL                COMMENT '实体名/偏好键(同用户同类型内活记录唯一)',
  content         VARCHAR(512)    NOT NULL                COMMENT '事实正文(自然语言摘要,不含密钥与原始 PII 串)',
  origin          VARCHAR(16)     NOT NULL                COMMENT '来源方式:AUTO_EXTRACT自动抽取 TOOL工具写入(预留未启用)',
  extractor       VARCHAR(32)     NULL                    COMMENT '抽取器标识与版本(如 llm-extract-v1),AUTO_EXTRACT 必填',
  confidence      DECIMAL(4,3)    NULL                    COMMENT '抽取置信度 0.000~1.000,可空',
  source_session_id BIGINT UNSIGNED NULL                 COMMENT '溯源:来源会话 id(→wx_message.session_id,可回看原话)',
  source_trace_id   VARCHAR(64)     NULL                    COMMENT '溯源:来源链路标识(→log_orchestration_trace,可回看当轮决策)',
  status          VARCHAR(16)     NOT NULL                COMMENT '状态:ACTIVE生效 SUPERSEDED已被新事实覆盖',
  supersedes_id   BIGINT UNSIGNED NULL                    COMMENT '本条覆盖掉的旧条 id(可空,构成覆盖链)',
  hit_count       INT             NOT NULL DEFAULT 1      COMMENT '累计出现次数(重复出现即强化)',
  first_seen_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '首次出现时间',
  last_seen_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近出现时间',
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
  updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted_at      DATETIME        NULL                    COMMENT '软删除时间:NULL 表示未删除',
  live_marker     TINYINT         GENERATED ALWAYS AS (IF(deleted_at IS NULL AND status = 'ACTIVE', 1, NULL)) STORED
                                                         COMMENT '活记录标记(生成列):未删除且ACTIVE=1,否则NULL;仅用于唯一约束,不可应用写入',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人状态库条目(跨会话生长的个人知识)';

-- 同用户同类型同名：至多一条活记录（覆盖写的数据库兜底，应用层查重为首要防线）
CREATE UNIQUE INDEX uk_openid_kind_name_live_marker
  ON biz_memory_item (openid, kind, name, live_marker);

-- 召回主路径：按用户取 ACTIVE 条目，按最近出现排序
CREATE INDEX idx_memory_openid_status_last_seen
  ON biz_memory_item (openid, status, last_seen_at);

-- 留存清理路径：SUPERSEDED 历史按 updated_at 过期物理删（ACTIVE 不自动清理，随用户删除而删）
CREATE INDEX idx_memory_status_updated_at
  ON biz_memory_item (status, updated_at);
