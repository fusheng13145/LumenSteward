-- ===========================================================================
-- V1.0.16__seed_rate_limit_config.sql
-- 数据域：系统域（sys_）种子 —— 限流与成本配额阈值（FR-20 / B-4，迭代 4 W5）
--
-- 为什么现在补：这三项自 MVP 起就被代码读取（ConfigKeys.RATE_LIMIT_*），却从未进过
-- sys_config，导致两个后果：
--   1) 后台配置页看不到、也改不了它们——FR-20 备选流 3a「阈值经 FR-18 免重启调整」
--      在运行期<b>没有出口</b>，只能改代码重发；
--   2) 成本看板的「预算」分母只能显示代码常量，管理者无法核对当前生效值。
-- 读取方一律「不存在即回退启动期静态值」，所以补种子<b>不改变任何既有行为</b>：
--   播种值与代码默认值逐项相等（200000 / 2000 / 空白名单），只是把隐藏值变成可见可改。
--
-- 值域校验（ConfigAdminService）：rate_limit.* 的 INT 项必须为正整数——
--   预算置 0 等于让所有对话立即进入降级，长度置 0 等于截断一切消息，都是不可用配置。
-- 幂等：Flyway 保证脚本仅执行一次。
-- ===========================================================================

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description) VALUES
  ('rate_limit.daily_token_budget', '200000', 'INT',    '200000', 0, 'rate_limit', 'LLM 日 token 预算:达 80% 告警、100% 降级为基础回复,次日零点自动恢复(FR-20 ③)'),
  ('rate_limit.max_message_length', '2000',   'INT',    '2000',   0, 'rate_limit', '单条消息最大字符数:超出按上限截断后继续处理(FR-20 ④)'),
  ('rate_limit.whitelist',          '',       'STRING', '',       0, 'rate_limit', '限流白名单:逗号分隔 openid,豁免频次/IP 限流(FR-20 备选流 2a)');
