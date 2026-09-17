-- ============================================================================
-- V1.0.5__fix_long_identifier_columns.sql
-- D7 修复：长标识列宽不足导致运行期写入「恒失败」。
--
-- 【缺陷】log_tool_call.trace_id 原为 VARCHAR(32)，而本系统 traceId 由
--        TraceContext.newTraceId() = UUID.randomUUID().toString() 产生，形如
--        0f8fad5b-d9cb-469f-a165-70867728950e，固定长度 36 位。
--        → INSERT 恒报 "Data too long for column 'trace_id' at row 1"，
--          且该异常此前被 best-effort 逻辑静默吞掉（D7 一并消除静默，见代码层），
--          最终导致 log_tool_call 运行期恒为空表：
--            · D3 的 call_seq 递增写不进表 → AC-B6/B7 失败；
--            · AC-E6 工具日志查询失败；
--            · ADR-003「工具调用日志同步落库」在运行期实际未生效。
--
-- 【修复】trace_id 加宽至 VARCHAR(64)（36 位 UUID + 预留扩容；UTF8MB4 下 64 字符足够）。
--
-- 【纪律】依据 SRS 7.6.3 / G-07：结构变更必须走增量脚本，禁止修改已发布的 V1.0.2。
--        本脚本仅执行 ALTER TABLE，不改写任何基线脚本（空库执行本目录脚本仍可一次建成全部表）。
--
-- 【核查范围】本次对 8 张表全部「长标识 / 富文本」列做了逐一核查（见
--        docs/evidence/index.md「D7 列宽核查表」）。结论：仅 trace_id 一处列宽不足，
--        其余列（openid 28 / unionid 28 / context_key "conv:"+openid≤69 / msg_id / config_key /
--        target ≤82 / avatar_url / media_id 等）均在 WeChat / 本系统取值范围内或已用 TEXT/JSON 承载，
--        无需变更。故本脚本只做一处 MODIFY。
-- ============================================================================

ALTER TABLE log_tool_call
  MODIFY COLUMN trace_id VARCHAR(64) NOT NULL COMMENT '链路标识(36位UUID,预留扩容至64)';
