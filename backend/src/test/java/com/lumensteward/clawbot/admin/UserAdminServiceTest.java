package com.lumensteward.clawbot.admin;

import com.lumensteward.clawbot.application.admin.UserAdminService;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户写服务单测（FR-16 AC②③ / T11）：启停与档案维护的前后值留痕。
 */
class UserAdminServiceTest {

    private final WxUserMapper mapper = mock(WxUserMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final UserAdminService service = new UserAdminService(mapper, auditLogService);

    @Test
    @DisplayName("AC③ 禁用用户：状态前后值留痕（1→0）")
    void shouldAuditStatusChange() {
        when(mapper.selectById(1L)).thenReturn(user("旺财主人", 1));

        service.updateStatus(1L, 0, 9L, "127.0.0.1");

        ArgumentCaptor<WxUserEntity> captor = ArgumentCaptor.forClass(WxUserEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isZero();
        verify(auditLogService).record(eq(9L), eq("USER"), eq("USER_DISABLE"), any(),
                eq("1"), eq("0"), isNull(), eq("127.0.0.1"), eq(1));
    }

    @Test
    @DisplayName("启用用户：动作标识为 USER_ENABLE")
    void shouldAuditEnable() {
        when(mapper.selectById(1L)).thenReturn(user("旺财主人", 0));

        service.updateStatus(1L, 1, 9L, "127.0.0.1");

        verify(auditLogService).record(eq(9L), eq("USER"), eq("USER_ENABLE"), any(),
                eq("0"), eq("1"), isNull(), eq("127.0.0.1"), eq(1));
    }

    @Test
    @DisplayName("状态取值非法 → 拒绝（仅 0/1）")
    void shouldRejectInvalidStatus() {
        assertThatThrownBy(() -> service.updateStatus(1L, 2, 9L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("状态取值非法");
        verify(mapper, never()).updateById(any(WxUserEntity.class));
    }

    @Test
    @DisplayName("AC③ 档案维护：昵称前后值留痕")
    void shouldAuditProfileChange() {
        when(mapper.selectById(1L)).thenReturn(user("旧昵称", 1));

        service.updateProfile(1L, "新昵称", 9L, "127.0.0.1", "用户要求更正");

        ArgumentCaptor<WxUserEntity> captor = ArgumentCaptor.forClass(WxUserEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("新昵称");
        verify(auditLogService).record(eq(9L), eq("USER"), eq("USER_PROFILE_UPDATE"), any(),
                eq("旧昵称"), eq("新昵称"), eq("用户要求更正"), eq("127.0.0.1"), eq(1));
    }

    @Test
    @DisplayName("档案无实质变更 → 不写库、不刷审计")
    void shouldSkipNoopProfileChange() {
        when(mapper.selectById(1L)).thenReturn(user("同名", 1));

        service.updateProfile(1L, "同名", 9L, "127.0.0.1", null);

        verify(mapper, never()).updateById(any(WxUserEntity.class));
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(),
                any(), anyInt());
    }

    @Test
    @DisplayName("昵称超长 → 拒绝（与 DDL 长度一致）")
    void shouldRejectTooLongNickname() {
        String tooLong = "x".repeat(65);
        assertThatThrownBy(() -> service.updateProfile(1L, tooLong, 9L, "127.0.0.1", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("昵称超长");
    }

    @Test
    @DisplayName("用户不存在 → 拒绝")
    void shouldRejectMissingUser() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateStatus(404L, 0, 9L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("用户不存在");
    }

    private static WxUserEntity user(String nickname, int status) {
        WxUserEntity entity = new WxUserEntity();
        entity.setId(1L);
        entity.setOpenid("openid-abcdefgh1234");
        entity.setNickname(nickname);
        entity.setStatus(status);
        return entity;
    }
}
