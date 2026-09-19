package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.SysConfigMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统配置查询（架构 4.3 / GET /api/configs、/api/configs/{key}/history）。
 *
 * <p><b>读写分离（迭代 2 T10）：</b>本服务只承担读；写（校验 / 落库 / 缓存失效 / 审计）由
 * {@link ConfigAdminService} 承担。此前写逻辑内联于此且"仅落库不热更新"，现统一收敛，
 * 避免同一份 update 逻辑在两处各自演化。
 *
 * <p>运行时取值方一律走 {@code DynamicConfigService}（FR-18 免重启生效）。
 */
@Service
public class ConfigQueryService {

    private final SysConfigMapper sysConfigMapper;
    private final AuditLogMapper auditLogMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param sysConfigMapper 配置 Mapper
     * @param auditLogMapper  审计 Mapper（变更历史查询）
     */
    public ConfigQueryService(SysConfigMapper sysConfigMapper, AuditLogMapper auditLogMapper) {
        this.sysConfigMapper = sysConfigMapper;
        this.auditLogMapper = auditLogMapper;
    }

    /**
     * 列出全部配置。
     *
     * @return 配置实体列表（按分类、键名排序）
     */
    public List<SysConfigEntity> list() {
        return sysConfigMapper.selectList(new LambdaQueryWrapper<SysConfigEntity>()
                .orderByAsc(SysConfigEntity::getCategory)
                .orderByAsc(SysConfigEntity::getConfigKey));
    }

    /**
     * 查询配置变更历史。
     *
     * @param configKey 键名
     * @return 审计记录列表（倒序）
     */
    public List<AuditLogEntity> history(String configKey) {
        return auditLogMapper.selectList(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getRegType, "CONFIG")
                .eq(AuditLogEntity::getTarget, configKey)
                .orderByDesc(AuditLogEntity::getCreatedAt)
                .last("LIMIT 200"));
    }
}
