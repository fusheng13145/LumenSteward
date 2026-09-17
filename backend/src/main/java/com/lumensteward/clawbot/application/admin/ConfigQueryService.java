package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.SysConfigMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统配置查询与变更（架构 4.3 / GET|PUT /api/configs、/api/configs/{key}/reset|history）。
 *
 * <p><b>MVP 边界（G-33 如实标注）：</b>读展示完整；写仅落库 + 审计，<b>不热更新</b>运行时配置
 * （切换 Mock/Real 仍需重启，AC-D3 由配置项本身保证零代码改动）。
 */
@Service
public class ConfigQueryService {

    private static final Logger log = LoggerFactory.getLogger(ConfigQueryService.class);

    private final SysConfigMapper sysConfigMapper;
    private final AuditLogMapper auditLogMapper;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param sysConfigMapper 配置 Mapper
     * @param auditLogMapper  审计 Mapper（变更历史查询）
     * @param auditLogService 审计写服务
     */
    public ConfigQueryService(SysConfigMapper sysConfigMapper,
                              AuditLogMapper auditLogMapper,
                              AuditLogService auditLogService) {
        this.sysConfigMapper = sysConfigMapper;
        this.auditLogMapper = auditLogMapper;
        this.auditLogService = auditLogService;
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
     * 批量更新配置（仅落库 + 审计，不热更新）。
     *
     * @param items   更新项
     * @param reason  变更原因（BR-25）
     * @param adminId 操作人
     * @param ip      来源 IP
     */
    public void update(List<ConfigItem> items, String reason, Long adminId, String ip) {
        if (items == null || items.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "更新项不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "变更原因必填（BR-25）");
        }
        for (ConfigItem item : items) {
            SysConfigEntity entity = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfigEntity>()
                    .eq(SysConfigEntity::getConfigKey, item.configKey())
                    .last("LIMIT 1"));
            if (entity == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "配置项不存在: " + item.configKey());
            }
            String before = MaskUtils.secret(entity.getConfigValue());
            entity.setConfigValue(item.configValue());
            entity.setUpdatedBy(adminId);
            sysConfigMapper.updateById(entity);
            String after = MaskUtils.secret(entity.getConfigValue());
            audit(adminId, "CONFIG_UPDATE", entity.getConfigKey(), before, after, reason, ip);
        }
        log.info("配置已落库（MVP 不热更新，需重启生效），共 {} 项", items.size());
    }

    /**
     * 恢复默认值（骨架实现：写回 default_value 并审计）。
     *
     * @param configKey 键名
     * @param adminId   操作人
     * @param ip        来源 IP
     */
    public void reset(String configKey, Long adminId, String ip) {
        SysConfigEntity entity = sysConfigMapper.selectOne(new LambdaQueryWrapper<SysConfigEntity>()
                .eq(SysConfigEntity::getConfigKey, configKey)
                .last("LIMIT 1"));
        if (entity == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "配置项不存在: " + configKey);
        }
        String before = MaskUtils.secret(entity.getConfigValue());
        entity.setConfigValue(entity.getDefaultValue());
        entity.setUpdatedBy(adminId);
        sysConfigMapper.updateById(entity);
        audit(adminId, "CONFIG_RESET", entity.getConfigKey(), before,
                MaskUtils.secret(entity.getConfigValue()), "恢复默认值", ip);
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

    private void audit(Long adminId, String action, String target, String before, String after,
                       String reason, String ip) {
        try {
            auditLogService.record(adminId, "CONFIG", action, target, before, after, reason, ip, 1);
        } catch (RuntimeException e) {
            log.warn("配置审计写失败: err={}", e.getMessage());
        }
    }

    /**
     * 配置更新项（application 内部模型）。
     *
     * @param configKey   键名
     * @param configValue 新值
     */
    public record ConfigItem(String configKey, String configValue) {
    }
}
