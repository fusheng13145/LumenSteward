package com.lumensteward.clawbot.infrastructure.bootstrap;

import com.lumensteward.clawbot.infrastructure.bootstrap.doctor.DependencyCheck;
import com.lumensteward.clawbot.infrastructure.bootstrap.doctor.DoctorReport;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Startup Doctor 单元测试（SUP-05 / AC-D4）。
 *
 * <p>覆盖：核心依赖不可达时报告降级且<b>不抛异常</b>；全部就绪时整体结论为 UP；
 * 报告项覆盖 DB/Redis/微信/LLM/TTS/物流/地图。
 */
class StartupDoctorImplTest {

    private static final WechatProperties WECHAT_MOCK =
            new WechatProperties("", "", "", "", true, 300, 300);
    private static final LlmProperties LLM_MOCK =
            new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);

    @Test
    @DisplayName("核心依赖不可达：返回降级报告且永不抛异常（AC-D4）")
    void shouldDegradeGracefullyWhenDependenciesDown() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("db down"));
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        when(redisFactory.getConnection()).thenThrow(new RedisConnectionFailureException("redis down"));

        StartupDoctorImpl doctor = new StartupDoctorImpl(
                dataSource, redisFactory, WECHAT_MOCK, LLM_MOCK, new MockEnvironment());

        DoctorReport report = assertDoesNotThrowReturning(doctor);
        assertThat(report.overall()).isEqualTo(DoctorReport.OVERALL_DOWN);
        assertThat(report.items()).extracting(DependencyCheck::name)
                .contains("MySQL", "Redis", "微信通道", "LLM", "TTS", "物流", "地图");
        assertThat(report.items())
                .filteredOn(item -> "MySQL".equals(item.name()))
                .singleElement()
                .extracting(DependencyCheck::connectivity)
                .isEqualTo(DependencyCheck.DOWN);
    }

    @Test
    @DisplayName("依赖就绪 + Mock 模式：整体结论为 UP")
    void shouldReportUpWhenDependenciesHealthy() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        RedisConnection redisConnection = mock(RedisConnection.class);
        when(redisConnection.ping()).thenReturn("PONG");
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        when(redisFactory.getConnection()).thenReturn(redisConnection);

        StartupDoctorImpl doctor = new StartupDoctorImpl(
                dataSource, redisFactory, WECHAT_MOCK, LLM_MOCK, new MockEnvironment());

        DoctorReport report = doctor.diagnose();
        assertThat(report.overall()).isEqualTo(DoctorReport.OVERALL_UP);
        assertThat(report.checkedAt()).isNotNull();
        List<DependencyCheck> items = report.items();
        assertThat(items).extracting(DependencyCheck::connectivity)
                .doesNotContain(DependencyCheck.DOWN, DependencyCheck.TIMEOUT);
    }

    private static DoctorReport assertDoesNotThrowReturning(StartupDoctorImpl doctor) {
        DoctorReport[] holder = new DoctorReport[1];
        assertThatCode(() -> holder[0] = doctor.diagnose()).doesNotThrowAnyException();
        assertThat(holder[0]).isNotNull();
        return holder[0];
    }
}
