package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计日志检索（架构 4.3 / GET /api/audit-logs，AUDITOR+）。
 *
 * <p>读-only 服务；写由 {@code AuditLogService} 承担。角色约束由控制器 {@code @PreAuthorize} 施加。
 */
@Service
public class AuditLogQueryService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param auditLogMapper 审计 Mapper
     */
    public AuditLogQueryService(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 分页检索审计日志。
     *
     * @param page      分页参数
     * @param regType   资源类型（可空）
     * @param action    操作标识（可空）
     * @param startTime 下界（可空）
     * @param endTime   上界（可空）
     * @return 分页结果
     */
    public PageResult<AuditLogEntity> page(PageQuery page, String regType, String action,
                                           LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(notBlank(regType), AuditLogEntity::getRegType, regType)
                .eq(notBlank(action), AuditLogEntity::getAction, action)
                .ge(startTime != null, AuditLogEntity::getCreatedAt, startTime)
                .le(endTime != null, AuditLogEntity::getCreatedAt, endTime)
                .orderByDesc(AuditLogEntity::getCreatedAt);
        Page<AuditLogEntity> mpPage = auditLogMapper.selectPage(page.toPage(), wrapper);
        return PageResult.from(mpPage);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
