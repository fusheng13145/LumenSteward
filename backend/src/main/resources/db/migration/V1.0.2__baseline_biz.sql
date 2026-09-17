-- ============================================================================
-- V1.0.2__baseline_biz.sql
-- 数据域：业务域（biz_）+ 日志域（log_）
-- 对应 SRS 7.6.3 的「03_biz_tables.sql」域切分。
-- 表清单：biz_pet_profile、log_tool_call
--   · 与 V1.0.0(sys_/log_)、V1.0.1(wx_) 无重复定义（G-06）
-- 命名校准（SRS 7.6.1 / 架构 3.1）：SRS 7.2 原表名 pet_profile→biz_pet_profile、tool_call_log→log_tool_call。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 表 7-4 biz_pet_profile（宠物档案）—— ⭐ 唯一约束的生成列方案（架构 3.3 / PRD Q4 陷阱）
--
-- 问题：SRS 7.2 表 7-4 与 PRD Q4 建议的 UNIQUE(openid, pet_name, deleted_at) 在 MySQL 下不可用。
--       MySQL 唯一索引中 NULL 不参与相等性比较，导致所有未删除行（deleted_at IS NULL）彼此不冲突，
--       可插入任意多条同名活宠物，无法落实「单用户宠物昵称唯一」（AC-C3）。
--
-- 方案：新增 STORED 生成列 live_marker = IF(deleted_at IS NULL, 1, NULL)，随 deleted_at 自动重算；
--       · 活记录：三列均非 NULL → (openid, pet_name, 1) 严格唯一 → AC-C3 拦截重复登记 ✅
--       · 已删除记录：live_marker = NULL → 不参与相等性 → 可存在多条历史删除行 → AC-C6 删除后重建 ✅
--       live_marker 是派生列，不是第二种软删除表示，完全保留 deleted_at 的既有语义（7.6.2）。
--
-- 工程提示：live_marker 为数据库生成列，MyBatis-Plus 实体（PetProfileEntity）不得声明该字段，
--           否则 INSERT/UPDATE 会因写入生成列而报错。
-- 双保险：① 应用层写事务内查重（首要，负责友好提示）；② 本唯一索引兜底（并发正确性）。
-- ---------------------------------------------------------------------------
CREATE TABLE biz_pet_profile (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  openid         VARCHAR(64)     NOT NULL                COMMENT '所属用户',
  pet_name       VARCHAR(32)     NOT NULL                COMMENT '宠物昵称(必填)',
  pet_type       VARCHAR(16)     NULL                    COMMENT '宠物类型:猫/狗/其他',
  breed          VARCHAR(64)     NULL                    COMMENT '品种',
  gender         VARCHAR(8)      NULL                    COMMENT '性别:公/母/未知',
  birthday       DATE            NULL                    COMMENT '生日(须 ≤ 今日)',
  weight_kg      DECIMAL(5,2)    NULL                    COMMENT '体重(kg),可空',
  personality    VARCHAR(255)    NULL                    COMMENT '性格描述',
  notes          VARCHAR(500)    NULL                    COMMENT '备注',
  photo_media_id VARCHAR(128)    NULL                    COMMENT '头像素材 ID',
  created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP                             COMMENT '创建时间',
  updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  deleted_at     DATETIME        NULL                    COMMENT '软删除时间:NULL 表示未删除',
  live_marker    TINYINT         GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED
                                                         COMMENT '活记录标记(生成列):未删除=1,已删除=NULL;仅用于唯一约束,不可应用写入',
  PRIMARY KEY (id),
  UNIQUE KEY uk_openid_pet_name_live_marker (openid, pet_name, live_marker),
  KEY idx_openid (openid),
  KEY idx_deleted_at (deleted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='宠物档案';

-- ---------------------------------------------------------------------------
-- 表 7-5 log_tool_call（工具调用日志）—— 同步写入（ADR-003 / 7.3，执行一致性校验的事实依据）
-- 只增不改（无 updated_at）。params_json 中敏感字段须脱敏后存储；result_json 可截断。
-- ---------------------------------------------------------------------------
CREATE TABLE log_tool_call (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  trace_id        VARCHAR(32)     NOT NULL                COMMENT '链路标识(与响应头 X-Trace-Id 同源)',
  openid          VARCHAR(64)     NOT NULL                COMMENT '发起用户',
  session_id      BIGINT UNSIGNED NOT NULL                COMMENT '会话(逻辑外键→wx_session.id)',
  tool_name       VARCHAR(64)     NOT NULL                COMMENT '工具名',
  call_seq        INT             NOT NULL                COMMENT '本次链路中的调用序号(体现编排顺序)',
  params_json     JSON            NOT NULL                COMMENT '入参(敏感字段脱敏后存储)',
  result_json     JSON            NULL                    COMMENT '结果(大结果可截断,保留关键字段)',
  status          TINYINT         NOT NULL                COMMENT '状态:0-成功 1-失败 2-降级 3-超时 4-未执行',
  error_type      VARCHAR(32)     NULL                    COMMENT '异常分类:L1/L2/L3/L4(对应 2.3.5 四层分类)',
  fallback_reason VARCHAR(255)    NULL                    COMMENT '降级原因(降级时必填,BR-24)',
  latency_ms      INT             NOT NULL                COMMENT '耗时(ms)',
  llm_round       TINYINT         NULL                    COMMENT '所属 Agent Loop 轮次(SC-01)',
  created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_openid_created_at (openid, created_at),
  KEY idx_tool_name_status_created_at (tool_name, status, created_at),
  KEY idx_trace_id (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='工具调用日志';
