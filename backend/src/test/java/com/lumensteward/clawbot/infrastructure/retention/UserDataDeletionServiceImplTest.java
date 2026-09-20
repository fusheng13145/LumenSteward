package com.lumensteward.clawbot.infrastructure.retention;

import com.lumensteward.clawbot.application.retention.DeletionScope;
import com.lumensteward.clawbot.application.retention.UserDataDeletionService.DeletionSummary;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.PetProfileMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户数据删除（细粒度 + 匿名化）单测（FR-19 ② / BR-27/28）。
 */
class UserDataDeletionServiceImplTest {

    private final WxMessageMapper messageMapper = mock(WxMessageMapper.class);
    private final WxSessionMapper sessionMapper = mock(WxSessionMapper.class);
    private final PetProfileMapper petProfileMapper = mock(PetProfileMapper.class);
    private final MemoryItemMapper memoryItemMapper = mock(MemoryItemMapper.class);
    private final ToolCallLogMapper toolLogMapper = mock(ToolCallLogMapper.class);
    private final WxUserMapper wxUserMapper = mock(WxUserMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final UserDataDeletionServiceImpl service = new UserDataDeletionServiceImpl(
            messageMapper, sessionMapper, petProfileMapper, memoryItemMapper,
            toolLogMapper, wxUserMapper, auditLogService);

    @Test
    @DisplayName("ALL 范围：清对话+档案+状态库，匿名化工具日志与账户锚点，留审计，无残留 PII")
    void allScopeCascadesAndAnonymizes() {
        when(messageMapper.delete(any())).thenReturn(10);
        when(sessionMapper.delete(any())).thenReturn(2);
        when(petProfileMapper.deleteAllByOpenid(anyString())).thenReturn(1);
        when(memoryItemMapper.deleteAllByOpenid(anyString())).thenReturn(6);
        when(toolLogMapper.anonymizeOpenid(anyString(), anyString())).thenReturn(5);
        when(wxUserMapper.anonymize(anyString(), anyString())).thenReturn(1);

        DeletionSummary s = service.deleteUserData("openid1", 1L, "1.1.1.1", DeletionScope.ALL);

        verify(messageMapper).delete(any());
        verify(sessionMapper).delete(any());
        verify(petProfileMapper).deleteAllByOpenid("openid1");
        verify(memoryItemMapper).deleteAllByOpenid("openid1");

        var anon = forClass(String.class);
        verify(toolLogMapper).anonymizeOpenid(eq("openid1"), anon.capture());
        verify(wxUserMapper).anonymize(eq("openid1"), anon.capture());
        assertThat(anon.getAllValues()).allSatisfy(v -> assertThat(v).startsWith("anon_"));

        verify(auditLogService).record(eq(1L), eq("DATA_DELETE"), eq("ALL"),
                anyString(), anyString(), anyString(), anyString(), eq("1.1.1.1"), eq(1));

        assertThat(s.messages()).isEqualTo(10);
        assertThat(s.sessions()).isEqualTo(2);
        assertThat(s.pets()).isEqualTo(1);
        assertThat(s.memories()).isEqualTo(6);
        assertThat(s.anonymizedLogs()).isEqualTo(5);
        assertThat(s.anonymizedUser()).isTrue();
    }

    @Test
    @DisplayName("CHAT 范围：删对话与由其派生的状态库条目，不动档案、不匿名化")
    void chatScopeOnlyChat() {
        when(messageMapper.delete(any())).thenReturn(3);
        when(sessionMapper.delete(any())).thenReturn(1);
        when(memoryItemMapper.deleteAllByOpenid(anyString())).thenReturn(2);

        DeletionSummary s = service.deleteUserData("openid1", null, "1.1.1.1", DeletionScope.CHAT);

        verify(messageMapper).delete(any());
        verify(sessionMapper).delete(any());
        // 派生 PII 随对话一并清除：只删原文会留下同源的「记住的事实」
        verify(memoryItemMapper).deleteAllByOpenid("openid1");
        verify(petProfileMapper, never()).deleteAllByOpenid(anyString());
        verify(toolLogMapper, never()).anonymizeOpenid(anyString(), anyString());
        verify(wxUserMapper, never()).anonymize(anyString(), anyString());
        assertThat(s.memories()).isEqualTo(2);
        assertThat(s.anonymizedUser()).isFalse();
    }

    @Test
    @DisplayName("PET 范围：仅删档案，不碰对话与状态库")
    void petScopeOnlyPet() {
        when(petProfileMapper.deleteAllByOpenid(anyString())).thenReturn(4);

        DeletionSummary s = service.deleteUserData("openid1", null, "1.1.1.1", DeletionScope.PET);

        verify(petProfileMapper).deleteAllByOpenid("openid1");
        verify(messageMapper, never()).delete(any());
        verify(sessionMapper, never()).delete(any());
        verify(memoryItemMapper, never()).deleteAllByOpenid(anyString());
        verify(toolLogMapper, never()).anonymizeOpenid(anyString(), anyString());
        assertThat(s.pets()).isEqualTo(4);
        assertThat(s.memories()).isZero();
    }

    @Test
    @DisplayName("openid 为空 → 抛 IllegalArgumentException")
    void nullOpenidRejected() {
        assertThatThrownBy(() -> service.deleteUserData(null, 1L, "1.1.1.1", DeletionScope.ALL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("scope 为 null → 默认 ALL（仍走匿名化）")
    void nullScopeDefaultsAll() {
        when(messageMapper.delete(any())).thenReturn(0);
        when(sessionMapper.delete(any())).thenReturn(0);
        when(petProfileMapper.deleteAllByOpenid(anyString())).thenReturn(0);
        when(toolLogMapper.anonymizeOpenid(anyString(), anyString())).thenReturn(0);
        when(wxUserMapper.anonymize(anyString(), anyString())).thenReturn(0);

        DeletionSummary s = service.deleteUserData("openid1", null, "1.1.1.1", null);

        verify(toolLogMapper).anonymizeOpenid(eq("openid1"), anyString());
        assertThat(s.scope()).isEqualTo(DeletionScope.ALL);
    }
}
