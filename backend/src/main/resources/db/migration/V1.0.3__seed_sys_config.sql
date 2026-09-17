-- ============================================================================
-- V1.0.3__seed_sys_config.sql
-- 对应 SRS 7.6.3 的「99_seed_data.sql」（种子数据）。
-- 内容：sys_config 初始化项（架构 3.6）——不含任何密钥明文（BR-20 / NFR-SE-03）。
--   · SECRET 类（wx.token）：以空值 + is_encrypted=1 占位，运行时经环境变量注入；
--     生产 profile 下若仍为空/占位，由 PlaceholderConfigValidator Fail-Fast（SUP-06 / AC-F3）。
--   · 其余项为可读默认值，与前端常量、运行时配置缓存同源。
-- 幂等：Flyway 保证脚本仅执行一次；此处使用普通 INSERT，不使用 INSERT ... ON DUPLICATE KEY。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description) VALUES
  ('llm.provider',                 'mock',        'STRING', 'mock',        0, 'llm',           'LLM 提供方:mock/real(AC-D3 切换点)'),
  ('llm.model',                    'mock-model',  'STRING', 'mock-model',  0, 'llm',           '模型名'),
  ('llm.base-url',                 '',            'STRING', '',            0, 'llm',           'real 模式下必填的服务地址'),
  ('llm.input-budget-tokens',      '8000',        'INT',    '8000',        0, 'llm',           '上下文输入预算(FR-04/Q3)'),
  ('llm.reserved-output-tokens',   '1000',        'INT',    '1000',        0, 'llm',           '输出预留 token(FR-04/Q3)'),
  ('orchestration.max-rounds',     '5',           'INT',    '5',           0, 'orchestration', 'Agent Loop 最大轮次(SC-01)'),
  ('orchestration.max-parallel-tools', '3',       'INT',    '3',           0, 'orchestration', '最大并行工具数(SC-02)'),
  ('orchestration.total-budget-ms',    '25000',   'INT',    '25000',       0, 'orchestration', '链路总预算(ms)(SC-03)'),
  ('orchestration.tool-timeout-ms',    '8000',    'INT',    '8000',        0, 'orchestration', '单工具超时(ms)(SC-03)'),
  ('orchestration.disabled-tools', '[]',          'JSON',   '[]',          0, 'orchestration', '工具开关:被禁用的工具名数组(FR-18)'),
  ('safety.fail-closed',           'true',        'BOOL',   'true',        0, 'security',      '内容安全不可用时是否 Fail-Closed(BR-12)'),
  ('safety.strict-mode',           'false',       'BOOL',   'false',       0, 'security',      '严格模式(9.4.5)'),
  ('fallback.timeout-text',        '我暂时无法回应，请稍后再试',               'STRING', '我暂时无法回应，请稍后再试',               0, 'text', '超时兜底文案(9.5)'),
  ('fallback.hallucination-text',  '抱歉，我暂时无法获取该项信息，请稍后重试', 'STRING', '抱歉，我暂时无法获取该项信息，请稍后重试', 0, 'text', '执行一致性校验兜底文案(BR-04)'),
  ('fallback.blocked-text',        '该内容我无法处理',                         'STRING', '该内容我无法处理',                         0, 'text', '内容安全拦截兜底文案(BR-12)'),
  ('wx.mock.enabled',              'true',        'BOOL',   'true',        0, 'security',      '微信 Mock 通道开关(SUP-01/AC-D1/D2)'),
  ('wx.token',                     '',            'SECRET', '',            1, 'security',      '微信 Token(经环境变量注入,禁止明文落库,BR-01/BR-20)');
