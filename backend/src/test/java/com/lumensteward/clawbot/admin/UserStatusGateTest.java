package com.lumensteward.clawbot.admin;

import com.lumensteward.clawbot.application.admin.UserStatusGate;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 用户状态闸门单测（FR-16 AC② / T11）：禁用用户不触发 LLM。
 */
class UserStatusGateTest {

    private final WxUserMapper mapper = mock(WxUserMapper.class);
    private final UserStatusGate gate = new UserStatusGate(mapper);

    @Test
    @DisplayName("status=0（禁用）→ 拦截")
    void shouldBlockDisabledUser() {
        when(mapper.selectOne(any())).thenReturn(user(0));
        assertThat(gate.isBlocked("openid-1")).isTrue();
    }

    @Test
    @DisplayName("status=1（正常）→ 放行")
    void shouldAllowActiveUser() {
        when(mapper.selectOne(any())).thenReturn(user(1));
        assertThat(gate.isBlocked("openid-1")).isFalse();
    }

    @Test
    @DisplayName("用户不存在（首交互）→ 放行，交由后续 upsert")
    void shouldAllowUnknownUser() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(gate.isBlocked("openid-new")).isFalse();
    }

    @Test
    @DisplayName("openid 为空 → 放行（不查询）")
    void shouldAllowBlankOpenid() {
        assertThat(gate.isBlocked(null)).isFalse();
        assertThat(gate.isBlocked("  ")).isFalse();
    }

    @Test
    @DisplayName("DB 查询失败 → 放行并告警（只读降级，取向与 9.5 一致）")
    void shouldAllowWhenDbFails() {
        when(mapper.selectOne(any())).thenThrow(new IllegalStateException("db down"));
        assertThat(gate.isBlocked("openid-1")).isFalse();
    }

    @Test
    @DisplayName("status 为 null → 视为正常（不因脏数据误拦）")
    void shouldAllowWhenStatusNull() {
        WxUserEntity entity = new WxUserEntity();
        entity.setOpenid("openid-1");
        when(mapper.selectOne(any())).thenReturn(entity);
        assertThat(gate.isBlocked("openid-1")).isFalse();
    }

    private static WxUserEntity user(int status) {
        WxUserEntity entity = new WxUserEntity();
        entity.setId(1L);
        entity.setOpenid("openid-1");
        entity.setStatus(status);
        return entity;
    }
}
