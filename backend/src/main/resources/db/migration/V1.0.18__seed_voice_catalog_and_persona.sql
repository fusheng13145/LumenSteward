-- ============================================================================
-- V1.0.18 语音音色目录 + 管家 persona 播种（迭代 4 W13）
-- ----------------------------------------------------------------------------
-- 幂等：Flyway 保证脚本仅执行一次。播种值与代码默认值逐字相等
-- （SynthesizeVoiceTool.DEFAULT_VOICE_CATALOG / AgentOrchestratorImpl.PERSONA_DEFAULT），
-- 既有行为零变更；越界拒绝与热改能力自本迁移起可用（FR-18 值域口径）。
-- ============================================================================

INSERT INTO sys_config (config_key, config_value, value_type, default_value, is_encrypted, category, description) VALUES
('tts.voice-catalog', '["default","gentle","calm","lively","story","news"]',
 'JSON', '["default","gentle","calm","lively","story","news"]', 0, 'tts',
 '语音合成可用音色白名单（W13；voice_id 越界运行期拒绝）'),
('tts.voice-default', 'default', 'STRING', 'default', 0, 'tts',
 '默认音色（voice_id 空时使用）'),
('orchestration.persona', '你是"衔光管家"，一个诚实、克制的微信助手。',
 'STRING', '你是"衔光管家"，一个诚实、克制的微信助手。', 0, 'orchestration',
 '管家 persona 段（仅管理员可配，W13；诚实原则尾段固定不在此列，BR-04）');
