-- ============================================================================
-- V1.0.20 承诺表 + 到期提醒参数播种（迭代 4 W7/W8 最小提醒切片，D2 的 P4 承担者）
-- ----------------------------------------------------------------------------
-- 定位：单一「到期提醒」触发类型 + 到点投递（W7 切片）；承诺经生长管道登记
-- （抽取器产出 kind=COMMITMENT，监听器路由至此表——兑现 V1.0.11 迁移注释「承诺不在
-- biz_memory_item」的预留），到点经客服消息投递（W8 切片），受 48h 窗口内条数预算约束。
-- 判据（P4）：biz_commitment.due_at ↔ 客服消息发出时刻 ↔（真人截图，待 W0-a）三者对齐。
-- 幂等：Flyway 保证脚本仅执行一次；播种值与代码默认逐字相等（零行为变更）。
-- ============================================================================

CREATE TABLE biz_commitment
(
    id               BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    openid           VARCHAR(64)  NOT NULL COMMENT '所属用户（脱敏后出参，BR-21）',
    content          VARCHAR(255) NOT NULL COMMENT '要提醒的事（用户原话的紧凑转述）',
    due_at           DATETIME     NOT NULL COMMENT '到期时刻（登记时由 ISO 或相对表达定格）',
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DELIVERED（取消属 D3 全动词集）',
    delivered_at     DATETIME NULL COMMENT '投递时刻（幂等闩：至多一次）',
    source_session_id BIGINT NULL COMMENT '溯源：来源会话 id（W6 口径）',
    source_trace_id  VARCHAR(64) NULL COMMENT '溯源：来源链路标识（FR-21）',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='承诺（W7/W8）：到点经客服消息投递的提醒';

CREATE INDEX idx_commitment_status_due ON biz_commitment (status, due_at);
CREATE INDEX idx_commitment_openid ON biz_commitment (openid);

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description)
VALUES ('reminder.enabled', 'true', 'BOOL', 'true', 0, 'reminder',
        '到期提醒投递调度开关（W7；登记随 memory.growth 闸门走）'),
       ('reminder.max-per-48h', '5', 'INT', '5', 0, 'reminder',
        '同一用户 48h 固定窗口内最多投递的提醒条数（客服消息频控的保守近似，正整数）');
