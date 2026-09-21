-- ============================================================================
-- V1.0.13__seed_gray_config.sql
-- 灰度开关与熔断阈值引导（FR-22 / 迭代 4 W2）。
--   · gray.<功能>.percent = 100 —— 灰度是<b>叠加</b>在功能主开关之上的第二道闸门，
--     默认 100 表示「不改变灰度上线前的行为」；放量动作是管理员把它调成 1/5/20 而不是打开它。
--     置 0 = 灰度关闭，且<b>白名单同时失效</b>（回滚必须能一刀切，否则 AC③ 行为无法恢复）。
--   · gray.<功能>.whitelist —— 逗号分隔 openid，供内部账号先行验证；只在比例 > 0 时参与判定。
--   · gray.breaker.* —— 熔断判据：窗口内 (L2~L4 异常数 / 链路轮次) 超错误率阈值，
--     或链路 P95 时延超阈值（阈值 0 表示该判据不启用），且样本数达标 → 30s 内自动把全部
--     灰度比例置 0（满足 FR-22 AC②「60s 内回滚」），并写 reg_type=GRAY 审计。
--   · 默认阈值取保守值：错误率 30%、P95 不启用（0）、最小样本 20 轮 / 5 分钟——
--     低峰期样本不足时宁可不动作，避免一条异常就把放量清零。
-- 幂等：Flyway 保证脚本仅执行一次；比例键值域由 ConfigAdminService 写入时校验（0~100）。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description) VALUES
  ('gray.memory_growth.percent',            '100',  'INT',    '100',  0, 'gray', '个人状态库生长的灰度比例:0~100,0=灰度关闭且白名单失效(FR-22)'),
  ('gray.memory_growth.whitelist',          '',     'STRING', '',     0, 'gray', '个人状态库生长的灰度白名单:逗号分隔 openid,比例>0 时优先命中(FR-22)'),
  ('gray.breaker.enabled',                  'true', 'BOOL',   'true', 0, 'gray', '灰度熔断开关:超阈值自动把灰度比例置 0(FR-22/BR-31)'),
  ('gray.breaker.window-minutes',           '5',    'INT',    '5',    0, 'gray', '熔断观测窗口(分钟)'),
  ('gray.breaker.min-samples',              '20',   'INT',    '20',   0, 'gray', '熔断最小样本:窗口内链路轮次不足则不回滚(防误熔断)'),
  ('gray.breaker.error-rate-percent',       '30',   'INT',    '30',   0, 'gray', '熔断错误率阈值(百分比):窗口内 L2~L4 异常数/链路轮次(FR-22 异常流 3a)'),
  ('gray.breaker.p95-ms',                   '0',    'INT',    '0',    0, 'gray', '熔断 P95 时延阈值(ms):0=不启用该判据');
