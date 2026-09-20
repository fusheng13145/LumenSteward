package com.lumensteward.clawbot.infrastructure.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.application.retention.DeletionScope;
import com.lumensteward.clawbot.application.retention.UserDataDeletionService;
import com.lumensteward.clawbot.application.retention.UserDataDeletionService.DeletionSummary;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.PetProfileMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * {@link UserDataDeletionService} 实现（FR-19 ②）。
 */
@Service
public class UserDataDeletionServiceImpl implements UserDataDeletionService {

    private static final Logger log = LoggerFactory.getLogger(UserDataDeletionServiceImpl.class);

    private final WxMessageMapper messageMapper;
    private final WxSessionMapper sessionMapper;
    private final PetProfileMapper petProfileMapper;
    private final MemoryItemMapper memoryItemMapper;
    private final ToolCallLogMapper toolLogMapper;
    private final WxUserMapper wxUserMapper;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param messageMapper     消息 Mapper
     * @param sessionMapper     会话 Mapper
     * @param petProfileMapper  宠物档案 Mapper
     * @param memoryItemMapper  个人状态库 Mapper（W6 派生 PII，随对话数据一并删除）
     * @param toolLogMapper     工具日志 Mapper
     * @param wxUserMapper      用户 Mapper
     * @param auditLogService   审计服务
     */
    public UserDataDeletionServiceImpl(WxMessageMapper messageMapper, WxSessionMapper sessionMapper,
                                      PetProfileMapper petProfileMapper, MemoryItemMapper memoryItemMapper,
                                      ToolCallLogMapper toolLogMapper,
                                      WxUserMapper wxUserMapper, AuditLogService auditLogService) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.petProfileMapper = petProfileMapper;
        this.memoryItemMapper = memoryItemMapper;
        this.toolLogMapper = toolLogMapper;
        this.wxUserMapper = wxUserMapper;
        this.auditLogService = auditLogService;
    }

    @Override
    public DeletionSummary deleteUserData(String openid, Long operatorId, String ip, DeletionScope scope) {
        if (openid == null || openid.isBlank()) {
            throw new IllegalArgumentException("openid 不可为空");
        }
        scope = scope == null ? DeletionScope.ALL : scope;

        int messages = 0;
        int sessions = 0;
        int pets = 0;
        int memories = 0;
        int anonymizedLogs = 0;
        boolean anonymizedUser = false;

        if (scope == DeletionScope.ALL || scope == DeletionScope.CHAT) {
            messages = messageMapper.delete(new LambdaQueryWrapper<WxMessageEntity>().eq(WxMessageEntity::getOpenid, openid));
            sessions = sessionMapper.delete(new LambdaQueryWrapper<WxSessionEntity>().eq(WxSessionEntity::getOpenid, openid));
            // W6：状态库条目是对话的**派生 PII**——只删原文而留下「管家记住的事实」，
            // 等于把用户信息换一份存起来，故与对话数据同范围、一并物理清除。
            memories = memoryItemMapper.deleteAllByOpenid(openid);
        }
        if (scope == DeletionScope.ALL || scope == DeletionScope.PET) {
            pets = petProfileMapper.deleteAllByOpenid(openid);
        }
        if (scope == DeletionScope.ALL) {
            String anon = "anon_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            anonymizedLogs = toolLogMapper.anonymizeOpenid(openid, anon);
            int userRows = wxUserMapper.anonymize(openid, anon);
            anonymizedUser = userRows > 0;
        }

        auditLogService.record(operatorId, "DATA_DELETE", scope.name(),
                MaskUtils.openid(openid), openid, "(已删除)",
                "用户数据删除请求", ip, 1);
        log.info("用户数据删除完成 openid={} scope={} messages={} sessions={} pets={} memories={} anonLogs={} anonUser={}",
                MaskUtils.openid(openid), scope, messages, sessions, pets, memories, anonymizedLogs, anonymizedUser);

        return new DeletionSummary(openid, scope, messages, sessions, pets, memories,
                anonymizedLogs, anonymizedUser);
    }
}
