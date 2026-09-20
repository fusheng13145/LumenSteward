-- ===========================================================================
-- V1.0.12__seed_memory_config.sql
-- 数据域：系统域（sys_）种子 —— 个人状态库运行时开关（迭代 4 W6 / §2.19）
--
-- 为什么单独一支脚本：与 V1.0.3 同一张表、同一类别（可读默认值，无密钥），
--   但业务表 biz_memory_item 属 V1.0.11；配置项独立便于回看「开关从何而来」。
--
-- 默认值口径（诚实性声明，G-32/G-33）：
--   · memory.growth.enabled = false —— 生长管道会在每条消息后<b>额外调用一次模型</b>，
--     有真实 token 成本，故<b>默认关闭、预留未启用</b>；由管理者在后台显式开启，
--     且开启后抽取消耗计入 FR-20 ③ 日预算。
--   · memory.recall.enabled = true —— 召回只多一次带索引的读查询、不调模型，
--     成本可忽略；在生长未开启时状态库为空，召回自然产出空块（无副作用）。
--   · 条数/字符上限是<b>防挤占上下文</b>的硬闸，独立于 ContextTrimmer 的 token 预算。
-- ===========================================================================

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description) VALUES
  ('memory.growth.enabled',    'false', 'BOOL', 'false', 0, 'memory', '个人状态库自动生长开关:默认关闭,开启后每条消息额外调用模型抽取事实(W6/FR-20成本)'),
  ('memory.growth.max-items',  '5',     'INT',  '5',     0, 'memory', '单次对话最多落库的事实条数(W6 防噪声写入)'),
  ('memory.recall.enabled',    'true',  'BOOL', 'true',  0, 'memory', '个人状态库召回注入开关(W6,仅增加一次索引读查询)'),
  ('memory.recall.max-items',  '8',     'INT',  '8',     0, 'memory', '召回注入的最大条数(W6 防上下文挤占)'),
  ('memory.recall.max-chars',  '600',   'INT',  '600',   0, 'memory', '召回注入的最大字符数(W6 防上下文挤占)');
