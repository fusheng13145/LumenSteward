-- ============================================================================
-- V1.0.19 长任务通道（迭代 4 W15）——任务表 + 执行参数播种
-- ----------------------------------------------------------------------------
-- 定位：任务表 + 有界线程池 + 重启续跑，不引入 MQ（AMQP 维持预留未启用；
-- 单拥有方万级规模下新增中间件的运维与故障面收益不成比例）。
-- 消费方：W14 生图等远超 SC 预算的慢任务；当前唯一任务类型为样例 handler
-- sample_report（取证与联调用）。判据：进程重启后未完成任务可恢复且不重复下发素材
-- ——由 delivered_at 幂等闩保证「至多一次投递」。
-- 幂等：Flyway 保证脚本仅执行一次；播种值与代码默认逐字相等（零行为变更）。
-- ============================================================================

CREATE TABLE biz_long_task
(
    id           BIGINT UNSIGNED AUTO_INCREMENT COMMENT '主键',
    task_type    VARCHAR(64)  NOT NULL COMMENT '任务类型（对应 LongTaskHandler.type()）',
    openid       VARCHAR(64)  NOT NULL COMMENT '发起用户（脱敏后出参，BR-21）',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
    payload_json TEXT COMMENT '入参 JSON（handler 自解释）',
    result_json  TEXT COMMENT '执行结果 JSON（成功时）',
    error_type   VARCHAR(64) NULL COMMENT '失败分类（TIMEOUT/NO_HANDLER/HANDLER_FAILED）',
    attempts     INT          NOT NULL DEFAULT 0 COMMENT '已尝试次数（每次 claim +1）',
    delivered_at DATETIME NULL COMMENT '结果投递时刻（幂等闩：至多一次投递）',
    trace_id     VARCHAR(64) NULL COMMENT '提交链路标识（FR-21）',
    started_at   DATETIME NULL COMMENT '最近一次开始执行时刻',
    finished_at  DATETIME NULL COMMENT '终态时刻',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='长任务通道（W15）：跨重启可续跑的慢任务';

CREATE INDEX idx_long_task_status ON biz_long_task (status);
CREATE INDEX idx_long_task_openid ON biz_long_task (openid);

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description)
VALUES ('longtask.pool-size', '2', 'INT', '2', 0, 'longtask',
        '长任务执行池线程数（有界；调整后重启生效）'),
       ('longtask.timeout-seconds', '60', 'INT', '60', 0, 'longtask',
        '单个长任务执行超时（秒，超时置 FAILED/TIMEOUT；须为正整数）');
