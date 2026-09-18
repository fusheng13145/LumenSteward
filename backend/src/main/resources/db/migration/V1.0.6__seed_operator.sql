-- ============================================================================
-- V1.0.5__seed_operator.sql
-- 初始运营管理员（OPERATOR）引导（架构 3.5 / Q7 / RBAC）。
--   · 插入 1 行 sys_admin_user，username='operator'，role='OPERATOR'。
--   · password_hash 使用「非可用占位标记」__ENV_INJECTED__（与 superadmin 同源）。
--   · AdminBootstrapRunner 启动时对所有占位账号统一注入 ADMIN_INIT_PASSWORD（BCrypt），
--     故 operator 与 superadmin 共享同一初始口令，便于本地登录与 e2e 验证（AC-E3）。
--   · 任何路径均禁止硬编码明文口令（BR-20 / NFR-SE-03）。
-- ============================================================================

INSERT INTO sys_admin_user (username, password_hash, display_name, role, status, fail_count) VALUES
  ('operator', '__ENV_INJECTED__', '运营管理员', 'OPERATOR', 1, 0);
