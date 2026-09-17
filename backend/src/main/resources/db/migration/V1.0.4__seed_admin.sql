-- ============================================================================
-- V1.0.4__seed_admin.sql
-- 初始管理员引导（架构 3.5 / Q7 / BR-20）。
--   · 仅插入 1 行 sys_admin_user，username='superadmin'，role='SUPER_ADMIN'。
--   · password_hash 使用「非可用占位标记」__ENV_INJECTED__（不是任何真实口令的哈希）。
--   · AdminBootstrapRunner（ApplicationRunner，@Order(1)）在启动时：
--       若发现 password_hash='__ENV_INJECTED__'，则以环境变量 ADMIN_INIT_PASSWORD 计算 BCrypt 覆写；
--       prod 下环境变量缺失即 Fail-Fast；local 下生成一次性随机口令并打印到控制台。
--   · 任何路径均禁止硬编码明文口令（BR-20 / NFR-SE-03）。
-- ============================================================================

INSERT INTO sys_admin_user (username, password_hash, display_name, role, status, fail_count) VALUES
  ('superadmin', '__ENV_INJECTED__', '超级管理员', 'SUPER_ADMIN', 1, 0);
