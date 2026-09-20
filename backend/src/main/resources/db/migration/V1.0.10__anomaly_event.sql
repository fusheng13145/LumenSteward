-- ===========================================================================
-- 表 log_anomaly_event（四层异常事件，SRS 2.3.5 / A-3 四层分布补全，迭代 4 W1）
--
-- 背景：A-3 降级看板的「四层异常分布」此前只读 log_tool_call.error_type，该表天然只承载
-- L3 工具层（+ SAFETY 审计的 L4），故 L1 接入层 / L2 认知层恒为 0（手册 §2.14 已如实标注）。
-- 本表把 L1/L2 的异常判定（验签失败、时间戳越界、幂等重复、未知消息类型、LLM 超时/不可用/
-- 输出非法）落成可查事实，使 SRS 2.3.5 的四层判据**逐层可核查**。
--
-- 设计边界：
--   1) 只增不改（无 updated_at / deleted_at），不承载业务状态；
--   2) layer 为**显式落库列**，不依赖错误码前缀推断（区别于 log_tool_call 的归类启发式）；
--   3) openid 仅存脱敏形态、detail 落库前截断（BR-21）；
--   4) 说明索引使用独立 CREATE INDEX 语句（MySQL 8.4 兼容写法，避免解析报错）。
-- ===========================================================================
CREATE TABLE log_anomaly_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  layer       VARCHAR(2)   NOT NULL               COMMENT '异常层次: L1接入层 L2认知层 L3工具层 L4输出层(SRS 2.3.5)',
  error_code  VARCHAR(32)  NOT NULL               COMMENT '异常码(如 SIGNATURE_INVALID / LLM_TIMEOUT)',
  source      VARCHAR(64)  NOT NULL               COMMENT '埋点来源组件(如 wechat.callback / dispatcher / orchestrator)',
  trace_id    VARCHAR(64)  NULL                   COMMENT '链路标识(与 X-Trace-Id 同源,可空)',
  openid      VARCHAR(64)  NULL                   COMMENT '脱敏后的用户标识(如 oabcd****wxyz),接入层异常可空',
  detail      VARCHAR(255) NULL                   COMMENT '异常摘要(落库前截断,不含 PII 与密钥)',
  created_at  DATETIME(3)  NOT NULL               COMMENT '创建时间',
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='四层异常事件(SRS 2.3.5)';

CREATE INDEX idx_anomaly_event_layer_created
  ON log_anomaly_event (layer, created_at);

CREATE INDEX idx_anomaly_event_created_at
  ON log_anomaly_event (created_at);
