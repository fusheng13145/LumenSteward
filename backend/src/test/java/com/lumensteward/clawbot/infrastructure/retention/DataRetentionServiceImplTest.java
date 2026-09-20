package com.lumensteward.clawbot.infrastructure.retention;

import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.PetProfileMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 数据保留定时清理单测（FR-19 ① / BR-27）。
 */
class DataRetentionServiceImplTest {

    private final WxMessageMapper messageMapper = mock(WxMessageMapper.class);
    private final WxSessionMapper sessionMapper = mock(WxSessionMapper.class);
    private final ToolCallLogMapper toolLogMapper = mock(ToolCallLogMapper.class);
    private final PetProfileMapper petProfileMapper = mock(PetProfileMapper.class);
    private final MemoryItemMapper memoryItemMapper = mock(MemoryItemMapper.class);
    private final DataRetentionServiceImpl service =
            new DataRetentionServiceImpl(messageMapper, sessionMapper, toolLogMapper,
                    petProfileMapper, memoryItemMapper);

    @Test
    @DisplayName("purgeExpiredMessages 按 createdAt<cutoff 删除并返回影响行数")
    void purgeMessagesReturnsCount() {
        when(messageMapper.delete(any())).thenReturn(7);
        LocalDateTime cutoff = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertThat(service.purgeExpiredMessages(cutoff)).isEqualTo(7);
        verify(messageMapper).delete(any());
    }

    @Test
    @DisplayName("purgeAll 串行清理五类数据，单任务失败不影响其余（BR-27 可证明但隔离）")
    void purgeAllIsolatesFailures() {
        when(messageMapper.delete(any())).thenThrow(new RuntimeException("db error"));
        when(sessionMapper.delete(any())).thenReturn(2);
        when(toolLogMapper.deleteBefore(any())).thenReturn(3);
        when(petProfileMapper.deletePhysicallyDeletedBefore(any())).thenReturn(4);
        when(memoryItemMapper.deleteSupersededBefore(any())).thenReturn(5);
        // 任一子任务异常都不应向上抛出
        service.purgeAll();
        verify(sessionMapper).delete(any());
        verify(toolLogMapper).deleteBefore(any());
        verify(petProfileMapper).deletePhysicallyDeletedBefore(any());
        verify(memoryItemMapper).deleteSupersededBefore(any());
    }

    @Test
    @DisplayName("各子清理方法分别命中对应 Mapper")
    void perTaskTargetsCorrectMapper() {
        service.purgeExpiredSessions(LocalDateTime.now());
        verify(sessionMapper).delete(any());

        service.purgeExpiredToolLogs(LocalDateTime.now());
        verify(toolLogMapper).deleteBefore(any());

        service.purgePhysicallyDeletedPets(LocalDateTime.now());
        verify(petProfileMapper).deletePhysicallyDeletedBefore(any());

        service.purgeSupersededMemories(LocalDateTime.now());
        verify(memoryItemMapper).deleteSupersededBefore(any());
    }

    @Test
    @DisplayName("状态库清理 cutoff 为空时不动库（防止误清全部历史）")
    void memoryPurgeIgnoresNullCutoff() {
        assertThat(service.purgeSupersededMemories(null)).isZero();
        verify(memoryItemMapper, never()).deleteSupersededBefore(any());
    }
}
