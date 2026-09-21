-- ============================================================================
-- V1.0.14__dark_launch_weather_tool.sql
-- 迭代 4 W3（FR-23 工具插件化）：示例插件 query_weather 的「1 条配置」。
--
-- 新增工具按 AC① 只允许「1 个类 + 1 条配置」——类是 QueryWeatherTool（零改引擎），
-- 配置即本条：把示例工具加入 FR-18 的工具禁用数组，默认不放量（其数据源只是内置
-- 样例城市，无真实预报能力，BR-09）。管理后台改回该键即可让模型看到它，无需发版。
--
-- 写法：JSON 追加 + 去重守卫，避免覆盖 OPERATOR 自行编辑过的禁用列表。
-- ============================================================================

UPDATE sys_config
   SET config_value = JSON_ARRAY_APPEND(CAST(config_value AS JSON), '$', 'query_weather')
 WHERE config_key = 'orchestration.disabled-tools'
   AND JSON_VALID(config_value)
   AND NOT JSON_CONTAINS(CAST(config_value AS JSON), JSON_QUOTE('query_weather'));
