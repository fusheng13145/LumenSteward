package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.infrastructure.persistence.entity.RateLimitLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.RateLimitLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流事件日志服务单测（FR-20 ④ / BR-29）。
 */
class RateLimitLogServiceImplTest {

    private final RateLimitLogMapper mapper = mock(RateLimitLogMapper.class);
    private final RateLimitLogServiceImpl service = new RateLimitLogServiceImpl(mapper);

    @Test
    @DisplayName("record 落库且填充时间")
    void recordInsertsWithTime() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 19, 10, 0);
        RateLimitLogEntity[] captured = new RateLimitLogEntity[1];
        doAnswer(inv -> {
            captured[0] = inv.<RateLimitLogEntity>getArgument(0);
            return null;
        }).when(mapper).insert((RateLimitLogEntity) any(RateLimitLogEntity.class));

        service.record("o***", "1.1.1.1", "USER_FREQ", now);

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getOpenid()).isEqualTo("o***");
        assertThat(captured[0].getLimitType()).isEqualTo("USER_FREQ");
        assertThat(captured[0].getHitAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("落库抛异常不向上传播（BR-29 保护优先，绝不影响主链路）")
    void insertFailureSwallowed() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(RateLimitLogEntity.class));
        // 不抛即为通过
        service.record("o***", "1.1.1.1", "IP_FREQ", LocalDateTime.now());
    }

    @Test
    @DisplayName("query 透传过滤条件给 selectList")
    void queryDelegates() {
        when(mapper.selectList(any())).thenReturn(List.of(new RateLimitLogEntity(), new RateLimitLogEntity()));
        List<RateLimitLogEntity> r = service.query("USER_FREQ", null, null, 10);
        assertThat(r).hasSize(2);
        verify(mapper).selectList(any());
    }
}
