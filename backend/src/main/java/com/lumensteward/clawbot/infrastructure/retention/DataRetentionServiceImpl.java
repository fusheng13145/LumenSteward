package com.lumensteward.clawbot.infrastructure.retention;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.application.retention.DataRetentionService;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.PetProfileMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * {@link DataRetentionService} 实现（FR-19 ①）。
 *
 * <p>每日凌晨 03:00 执行（cron 秒 分 时 ...）：超出保留期的消息/会话/工具日志删除，
 * 软删超过 {@code PET_SOFT_DELETE_GRACE_DAYS} 天的宠物档案物理清除，
 * 覆盖超过 {@code MEMORY_HISTORY_RETENTION_DAYS} 天的状态库历史条目物理清除
 * （生效事实不自动清除，见 {@link DataRetentionService#MEMORY_HISTORY_RETENTION_DAYS}）。
 * 各子任务独立 try/catch，单任务失败不影响其余（BR-27 删除优先但须可证明）。
 */
@Service
public class DataRetentionServiceImpl implements DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionServiceImpl.class);

    private final WxMessageMapper messageMapper;
    private final WxSessionMapper sessionMapper;
    private final ToolCallLogMapper toolLogMapper;
    private final PetProfileMapper petProfileMapper;
    private final MemoryItemMapper memoryItemMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param messageMapper     消息 Mapper
     * @param sessionMapper     会话 Mapper
     * @param toolLogMapper     工具日志 Mapper
     * @param petProfileMapper  宠物档案 Mapper
     * @param memoryItemMapper  个人状态库 Mapper（W6：仅清理已覆盖历史）
     */
    public DataRetentionServiceImpl(WxMessageMapper messageMapper, WxSessionMapper sessionMapper,
                                   ToolCallLogMapper toolLogMapper, PetProfileMapper petProfileMapper,
                                   MemoryItemMapper memoryItemMapper) {
        this.messageMapper = messageMapper;
        this.sessionMapper = sessionMapper;
        this.toolLogMapper = toolLogMapper;
        this.petProfileMapper = petProfileMapper;
        this.memoryItemMapper = memoryItemMapper;
    }

    @Override
    @Scheduled(cron = "0 0 3 * * ?")
    public void purgeAll() {
        LocalDateTime now = LocalDateTime.now();
        int messages = safe(() -> purgeExpiredMessages(now.minusDays(MESSAGE_RETENTION_DAYS)), "消息");
        int sessions = safe(() -> purgeExpiredSessions(now.minusDays(MESSAGE_RETENTION_DAYS)), "会话");
        int toolLogs = safe(() -> purgeExpiredToolLogs(now.minusDays(TOOL_LOG_RETENTION_DAYS)), "工具日志");
        int pets = safe(() -> purgePhysicallyDeletedPets(now.minusDays(PET_SOFT_DELETE_GRACE_DAYS)), "软删档案");
        int memoryHistory = safe(() -> purgeSupersededMemories(
                now.minusDays(MEMORY_HISTORY_RETENTION_DAYS)), "状态库历史");
        log.info("数据保留定时清理完成 messages={} sessions={} toolLogs={} pets={} memoryHistory={}",
                messages, sessions, toolLogs, pets, memoryHistory);
    }

    @Override
    public int purgeExpiredMessages(LocalDateTime cutoff) {
        return messageMapper.delete(new LambdaQueryWrapper<WxMessageEntity>()
                .lt(WxMessageEntity::getCreatedAt, cutoff));
    }

    @Override
    public int purgeExpiredSessions(LocalDateTime cutoff) {
        return sessionMapper.delete(new LambdaQueryWrapper<WxSessionEntity>()
                .lt(WxSessionEntity::getCreatedAt, cutoff));
    }

    @Override
    public int purgeExpiredToolLogs(LocalDateTime cutoff) {
        return toolLogMapper.deleteBefore(cutoff);
    }

    @Override
    public int purgePhysicallyDeletedPets(LocalDateTime cutoff) {
        return petProfileMapper.deletePhysicallyDeletedBefore(cutoff);
    }

    @Override
    public int purgeSupersededMemories(LocalDateTime cutoff) {
        // 物理删除走裸 SQL：cutoff 为空时条件退化为三值逻辑，删除范围不可证明，故直接不动库
        if (cutoff == null) {
            return 0;
        }
        return memoryItemMapper.deleteSupersededBefore(cutoff);
    }

    private int safe(java.util.function.IntSupplier action, String label) {
        try {
            return action.getAsInt();
        } catch (RuntimeException e) {
            log.error("数据保留清理失败（{}）: err={}", label, e.getMessage());
            return 0;
        }
    }
}
