package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link AuditLogService} 的 MyBatis-Plus 实现。
 *
 * <p>审计写失败不抛出（SRS 9.5 DB 不可用 → 只读降级），仅记录 WARN。
 */
@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogServiceImpl.class);

    private final AuditLogMapper auditLogMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param auditLogMapper 审计 Mapper
     */
    public AuditLogServiceImpl(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public void record(Long adminId, String regType, String action, String target, String before,
                       String after, String reason, String ip, int result) {
        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setAdminId(adminId);
            entity.setRegType(regType);
            entity.setAction(action);
            entity.setTarget(target);
            entity.setBeforeValue(before);
            entity.setAfterValue(after);
            entity.setReason(reason);
            entity.setIp(ip);
            entity.setResult(result);
            auditLogMapper.insert(entity);
        } catch (RuntimeException e) {
            log.warn("审计落库失败（只读降级）: err={}", e.getMessage());
        }
    }
}
