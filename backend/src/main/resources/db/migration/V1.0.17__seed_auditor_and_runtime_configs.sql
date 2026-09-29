-- ===========================================================================
-- V1.0.17__seed_auditor_and_runtime_configs.sql
-- 数据域：系统域（sys_）种子 —— 审计员账号 + 两项"代码可读、库里不可见"的运行时配置
-- 来源：迭代 4 收尾（§7.18）
--
-- 1) AUDITOR 初始账号：三角色口径自迭代 1 起即存在，但引导种子只有 superadmin / operator，
--    导致「AUDITOR 可读/不可见」口径连续六轮只有权限表声明、无运行期样本（§7.11~§7.15
--    同一缺口）。password_hash 与既有两账号同源（__ENV_INJECTED__ 占位，
--    AdminBootstrapRunner 启动时统一注入 ADMIN_INIT_PASSWORD）。
--    幂等：username 已存在则跳过。
--
-- 2) llm.timeout-seconds：自 MVP 起被 ConfigKeys.LLM_TIMEOUT_SECONDS 读取（orchestrator
--    每次 LLM 调用取该值），却从未进 sys_config——后台看不到、改不了。播种值 15 与
--    LlmProperties 的 @DefaultValue("15") 逐字相等 ⇒ 既有行为零变更；值域校验
--    （ConfigAdminService）已同步收紧为正整数（置 0/负数等于每次调用即超时）。
--
-- 3) wechat.welcome-message：关注欢迎语正文（W20 首发体验）。此前硬编码在
--    EventMessageHandler；播种值与其默认文案逐字相等 ⇒ 行为零变更；能力自述段由
--    CapabilitySections 按实际下发集动态生成，不在此配置。
-- 读取方一律「不存在即回退代码默认」，三项种子均不改变任何既有行为。
-- 幂等：Flyway 保证脚本仅执行一次。
-- ===========================================================================

INSERT INTO sys_admin_user (username, password_hash, display_name, role, status, fail_count)
SELECT 'auditor', '__ENV_INJECTED__', '审计员', 'AUDITOR', 1, 0
WHERE NOT EXISTS (SELECT 1 FROM sys_admin_user WHERE username = 'auditor');

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description) VALUES
  ('llm.timeout-seconds', '15', 'INT', '15', 0, 'llm', 'LLM 对话调用超时秒数(EI-07):超时进入降级链路,须为正整数'),
  ('wechat.welcome-message', '你好呀，我是衔光管家～有任何想聊的、想记的，都可以直接告诉我。', 'STRING', '', 0, 'wechat', '关注欢迎语正文(W20):能力自述段由实际下发工具集自动追加,此处只配正文');
