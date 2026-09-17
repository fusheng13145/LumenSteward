-- ============================================================================
-- V1.0.1__baseline_wx.sql
-- 数据域：微信域（wx_）
-- 对应 SRS 7.6.3 的「02_wx_tables.sql」域切分。
-- 表清单：wx_user、wx_session、wx_message
--   · 与 V1.0.0(sys_/log_)、V1.0.2(biz_/log_) 无重复定义（G-06）
-- 命名校准（SRS 7.6.1 / 架构 3.1）：SRS 7.2 原表名 session→wx_session、message→wx_message。
-- 外键：session_id 等为逻辑外键，不建物理 FOREIGN KEY。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 表 7-1 wx_user（微信用户）
-- 软删除：deleted_at（NULL 表示未删除，7.6.2）；更新/软删走实体 @TableLogic(value="null", delval="now()")。
-- ---------------------------------------------------------------------------
CREATE TABLE wx_user (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  openid           VARCHAR(64)   NOT NULL                COMMENT '微信唯一标识(个人信息,索引设计考虑脱敏检索)',
  unionid          VARCHAR(64)   NULL                    COMMENT '开放平台 UnionId,未绑定为 NULL',
  nickname         VARCHAR(64)   NULL                    COMMENT '昵称(个人信息,展示须脱敏)',
  avatar_url       VARCHAR(512)  NULL                    COMMENT '头像链接',
  status           TINYINT       NOT NULL DEFAULT 1      COMMENT '状态:1-正常 0-禁用',
  last_interact_at DATETIME      NULL                    COMMENT '最后交互时间(活跃度统计)',
  created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间(首次交互)',
  updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted_at       DATETIME      NULL                    COMMENT '软删除时间:NULL 表示未删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_openid (openid),
  KEY idx_status_last_interact_at (status, last_interact_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='微信用户';

-- ---------------------------------------------------------------------------
-- 表 7-2 wx_session（会话）
-- context_key 恒为 conv:{openid}，故设为 NOT NULL，使唯一约束真正生效。
-- 会话不软删（7.6.2 审计口径），故无 deleted_at。
-- ---------------------------------------------------------------------------
CREATE TABLE wx_session (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  openid         VARCHAR(64)   NOT NULL                COMMENT '会话归属用户',
  context_key    VARCHAR(128)  NOT NULL                COMMENT 'Redis 上下文键,格式 conv:{openid}',
  state          VARCHAR(16)   NOT NULL DEFAULT 'IDLE' COMMENT '会话状态:IDLE/CHATTING/TASKING/DEGRADED',
  task_context   JSON          NULL                    COMMENT '任务型会话槽位缓存(与 LLM 上下文分离)',
  turn_count     INT           NOT NULL DEFAULT 0      COMMENT '累计轮次(统计用)',
  last_active_at DATETIME      NOT NULL                COMMENT '最后活跃时间(TTL 依据,默认 24h)',
  created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
  updated_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_context_key (context_key),
  KEY idx_openid (openid),
  KEY idx_last_active_at (last_active_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话';

-- ---------------------------------------------------------------------------
-- 表 7-3 wx_message（消息）
-- 只增不改（无 updated_at）；msg_id 为追溯用普通索引（非唯一，去重以 Redis dedup:msg: 为准）。
-- ---------------------------------------------------------------------------
CREATE TABLE wx_message (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  session_id  BIGINT UNSIGNED NOT NULL                COMMENT '所属会话(逻辑外键→wx_session.id)',
  openid      VARCHAR(64)     NOT NULL                COMMENT '冗余字段,便于按用户查询与删除',
  msg_id      VARCHAR(64)     NULL                    COMMENT '微信 MsgId(用于幂等追溯)',
  role        VARCHAR(16)     NOT NULL                COMMENT '角色:user/assistant/tool',
  msg_type    VARCHAR(16)     NOT NULL                COMMENT '消息类型:text/image/voice/location/event',
  content     TEXT            NOT NULL                COMMENT '消息内容(个人信息载体)',
  media_id    VARCHAR(128)    NULL                    COMMENT '关联素材标识',
  tool_name   VARCHAR(64)     NULL                    COMMENT '若 role=tool,记录工具名',
  token_count INT             NULL                    COMMENT '估算 token 数(用于上下文裁剪)',
  send_status TINYINT         NULL                    COMMENT '发送状态:0-待发 1-成功 2-失败',
  created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_session_id_created_at (session_id, created_at),
  KEY idx_openid_created_at (openid, created_at),
  KEY idx_created_at (created_at),
  KEY idx_msg_id (msg_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='消息';
