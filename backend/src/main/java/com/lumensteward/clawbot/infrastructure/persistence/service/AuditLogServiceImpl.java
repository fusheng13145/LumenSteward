package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.infrastructure.observability.PersistenceWriteFailureReporter;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import org.springframework.stereotype.Service;

/**
 * {@link AuditLogService} 的 MyBatis-Plus 实现。
 *
 * <p>审计写失败不抛出（SRS 9.5 DB 不可用 → 只读降级），但<b>不再静默</b>：经
 * {@link PersistenceWriteFailureReporter} 以 ERROR 级日志（含表名/列名/完整异常）并计入指标
 * {@code persistence.write.failures}（D7 修复）。审计属安全可比性证据，失败必须可见。
 */
@Service
public class AuditLogServiceImpl implements AuditLogService {

    /** 本服务写入的目标表名（用于失败上报）。 */
    private static final String TABLE = "log_audit";

    private final AuditLogMapper auditLogMapper;
    private final PersistenceWriteFailureReporter writeFailureReporter;

    /**
     * 构造器注入（G-14）。
     *
     * @param auditLogMapper        审计 Mapper
     * @param writeFailureReporter  写入失败上报器（日志 + 指标）
     */
    public AuditLogServiceImpl(AuditLogMapper auditLogMapper,
                               PersistenceWriteFailureReporter writeFailureReporter) {
        this.auditLogMapper = auditLogMapper;
        this.writeFailureReporter = writeFailureReporter;
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
            writeFailureReporter.report(TABLE, e);
        }
    }
}
