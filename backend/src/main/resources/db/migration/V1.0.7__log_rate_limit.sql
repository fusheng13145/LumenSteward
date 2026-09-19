-- ===========================================================================
-- 表 log_rate_limit（限流事件日志，FR-20 ④ / FR-17 可检索）
-- 高频率、低敏感：openid 仅存脱敏形态，不落原始 PII。
-- ===========================================================================
CREATE TABLE log_rate_limit (
  id         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  openid     VARCHAR(64)  NULL                  COMMENT '脱敏后的用户标识(如 oabcd****wxyz)',
  ip         VARCHAR(45)  NULL                  COMMENT '来源 IP',
  limit_type VARCHAR(20)  NOT NULL              COMMENT '限流类型:USER_FREQ/IP_FREQ',
  hit_at     DATETIME     NOT NULL              COMMENT '触发时间',
  created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_limit_type_hit_at (limit_type, hit_at),
  KEY idx_hit_at (hit_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='限流事件日志';
