package com.lumensteward.clawbot.application.reminder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 到期时间解析器测试（迭代 4 W7/W8）：相对时长定格、ISO 绝对时间、非法输入拒绝。
 */
class DueTimeResolverTest {

    private final DueTimeResolver resolver = new DueTimeResolver(ZoneId.of("Asia/Shanghai"));

    @Test
    @DisplayName("相对时长：+PT30M / +PT2H / +P1D 按解析时刻定格为绝对时间")
    void shouldResolveRelativeDurations() {
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

        LocalDateTime plus30m = resolver.resolve("+PT30M").orElseThrow();
        assertThat(plus30m).isAfterOrEqualTo(before.plusMinutes(29)).isBefore(before.plusMinutes(32));

        LocalDateTime plus2h = resolver.resolve("+PT2H").orElseThrow();
        assertThat(plus2h).isAfterOrEqualTo(before.plusMinutes(119)).isBefore(before.plusMinutes(122));

        LocalDateTime plus1d = resolver.resolve("+P1D").orElseThrow();
        assertThat(plus1d).isAfterOrEqualTo(before.plusHours(23));
    }

    @Test
    @DisplayName("ISO-8601 本地日期时间解析；过期时刻按已到期保留（宁早勿丢）")
    void shouldResolveIsoDateTime() {
        LocalDateTime past = resolver.resolve("2026-01-01T09:30").orElseThrow();
        assertThat(past).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 30));

        LocalDateTime future = resolver.resolve("2030-12-31T23:59:00").orElseThrow();
        assertThat(future.getYear()).isEqualTo(2030);
    }

    @Test
    @DisplayName("非法输入一律拒绝：自然语言 / 零时长 / 空值 / 荒谬年份")
    void shouldRejectUnparseableSpecs() {
        assertThat(resolver.resolve("明天下午三点")).isEmpty();
        assertThat(resolver.resolve("+PT0M")).isEmpty();
        assertThat(resolver.resolve("+PTM")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("  ")).isEmpty();
        assertThat(resolver.resolve("1999-01-01T00:00")).isEmpty();
        assertThat(resolver.resolve("+PT30S")).isEmpty();
    }
}
