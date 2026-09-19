-- ===========================================================================
-- 数据保留定时清理所需的索引（FR-19 ①）。
-- 避免每日全表扫描：按 created_at 范围删除走索引。
-- ===========================================================================

-- wx_message.created_at
CREATE INDEX idx_wx_message_created_at
  ON wx_message (created_at);

-- wx_session.created_at
CREATE INDEX idx_wx_session_created_at
  ON wx_session (created_at);

-- log_tool_call.created_at（物理清理）
CREATE INDEX idx_tool_call_created_at
  ON log_tool_call (created_at);

-- biz_pet_profile.deleted_at（软删 30 天后物理清除）
CREATE INDEX idx_pet_profile_deleted_at
  ON biz_pet_profile (deleted_at);
