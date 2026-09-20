-- ===========================================================================
-- 表 log_orchestration_trace（链路时序 span 快照，A-5 超时预算 / 迭代 3 Wave2 T6）
-- 每次编排链路结束落一行：span_json 保存该链路的 LLM 轮次与工具调用时间轴，
-- 管理后台按 trace_id 查询并渲染「单次链路时序瀑布图」。
-- openid 仅存脱敏形态，不落原始 PII（BR-21）；表只增不改。
-- 说明：索引使用独立 CREATE INDEX 语句（MySQL 8.4 兼容写法，避免解析报错）。
-- ===========================================================================
CREATE TABLE log_orchestration_trace (
  id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  trace_id        VARCHAR(64)  NOT NULL               COMMENT '链路标识(与 X-Trace-Id 同源)',
  openid          VARCHAR(64)  NOT NULL               COMMENT '脱敏后的用户标识(如 oabcd****wxyz)',
  session_id      BIGINT       NULL                   COMMENT '会话 id(逻辑外键 -> wx_session.id)',
  total_ms        INT          NOT NULL               COMMENT '链路总耗时(ms)',
  total_budget_ms INT          NOT NULL               COMMENT '链路总时间预算(ms,SC-03)',
  rounds          INT          NOT NULL               COMMENT 'Agent Loop 轮次',
  exceeded_budget TINYINT(1)   NOT NULL DEFAULT 0     COMMENT '是否超预算:1-是 0-否',
  span_json       JSON         NOT NULL               COMMENT 'span 数组 JSON(时序瀑布数据)',
  created_at      DATETIME(3)  NOT NULL               COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='链路时序 span 快照';

CREATE INDEX idx_orchestration_trace_trace_id
  ON log_orchestration_trace (trace_id);

CREATE INDEX idx_orchestration_trace_created_at
  ON log_orchestration_trace (created_at);
