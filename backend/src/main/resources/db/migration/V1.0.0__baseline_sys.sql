-- ============================================================================
-- V1.0.0__baseline_sys.sql
-- 数据域：系统域（sys_ / 审计日志域 log_）
-- 对应 SRS 7.6.3 的「01_sys_tables.sql」域切分；采用 Flyway 版本化文件名，
-- 使「空库执行即可建成全部表」与「结构变更走增量、禁止改已发布脚本」（G-07）同时成立。
-- 表清单：sys_admin_user、sys_config、log_audit
--   · 与 V1.0.1(wx_)、V1.0.2(biz_/log_) 无重复定义（G-06：同一张表只在唯一一个脚本中定义）
-- 规范：ENGINE=InnoDB、utf8mb4/utf8mb4_unicode_ci（G-05）；字段 COMMENT 齐备、枚举取值写入注释；
--       唯一键 uk_、普通索引 idx_、复合索引 idx_<列1>_<列2>（G-02）。
-- 审计字段：created_at/updated_at 的 DDL 默认值与实体 @TableField(fill) 注解双保障（G-03/7.6.2）。
-- 外键：本项目统一使用「逻辑外键」（列名约定 <表>_id），不建物理 FOREIGN KEY，避免分库/清理阻塞。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 表 7-6 sys_admin_user（管理员）
-- ---------------------------------------------------------------------------
CREATE TABLE sys_admin_user (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  username      VARCHAR(64)  NOT NULL                COMMENT '登录名',
  password_hash VARCHAR(128) NOT NULL                COMMENT 'BCrypt 密码哈希(含盐)',
  display_name  VARCHAR(64)  NULL                    COMMENT '显示名',
  role          VARCHAR(32)  NOT NULL                COMMENT '角色:SUPER_ADMIN/OPERATOR/AUDITOR',
  status        TINYINT      NOT NULL DEFAULT 1      COMMENT '状态:1-启用 0-禁用',
  fail_count    TINYINT      NOT NULL DEFAULT 0      COMMENT '连续登录失败次数',
  locked_until  DATETIME     NULL                    COMMENT '锁定截止时间',
  last_login_at DATETIME     NULL                    COMMENT '上次登录时间',
  last_login_ip VARCHAR(45)  NULL                    COMMENT '上次登录 IP(IPv6 兼容)',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                              COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_username (username),
  KEY idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统管理员';

-- ---------------------------------------------------------------------------
-- 表 7-7 sys_config（系统配置）
-- MVP 只读展示（PRD Q/P1-06）；SECRET 类型出参仅返回尾号。
-- 密钥类配置（llm.api-key / wx.token / tts.key / map.key / logistics.key）经环境变量注入，不落明文（BR-20）。
-- ---------------------------------------------------------------------------
CREATE TABLE sys_config (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  config_key    VARCHAR(64)  NOT NULL                COMMENT '键名,命名规范 模块.子项(如 llm.model)',
  config_value  TEXT         NOT NULL                COMMENT '值(敏感项为 AES 密文)',
  value_type    VARCHAR(16)  NOT NULL                COMMENT '值类型:STRING/INT/DECIMAL/BOOL/JSON/SECRET',
  default_value TEXT         NULL                    COMMENT '默认值(用于恢复)',
  is_encrypted  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否加密:1-是 0-否',
  category      VARCHAR(32)  NULL                    COMMENT '分类:llm/tts/orchestration/security/text/tool',
  description   VARCHAR(255) NULL                    COMMENT '说明',
  updated_by    BIGINT UNSIGNED NULL                 COMMENT '修改人(逻辑外键→sys_admin_user.id)',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP                              COMMENT '创建时间',
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP  COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_config_key (config_key),
  KEY idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置';

-- ---------------------------------------------------------------------------
-- 表 7-8 log_audit（审计日志）
-- 只增不改（无 updated_at）；before_value/after_value 中的个人信息须脱敏（BR-22）。
-- ---------------------------------------------------------------------------
CREATE TABLE log_audit (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  admin_id     BIGINT UNSIGNED NULL                    COMMENT '操作人(系统任务为 NULL,逻辑外键→sys_admin_user.id)',
  reg_type     VARCHAR(32)  NOT NULL                   COMMENT '资源类型:CONFIG/USER/PROFILE/AUTH/DATA_DELETE',
  action       VARCHAR(64)  NOT NULL                   COMMENT '操作标识(如 USER_DISABLE/CONFIG_UPDATE)',
  target       VARCHAR(128) NULL                       COMMENT '操作对象(脱敏标识)',
  before_value TEXT         NULL                       COMMENT '变更前值(个人信息须脱敏)',
  after_value  TEXT         NULL                       COMMENT '变更后值',
  reason       VARCHAR(255) NULL                       COMMENT '变更原因',
  ip           VARCHAR(45)  NULL                       COMMENT '来源 IP',
  result       TINYINT      NOT NULL                   COMMENT '结果:0-失败 1-成功',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_reg_type_created_at (reg_type, created_at),
  KEY idx_admin_id_created_at (admin_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志';
