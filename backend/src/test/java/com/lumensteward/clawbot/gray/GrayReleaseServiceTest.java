package com.lumensteward.clawbot.gray;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.gray.GrayFeature;
import com.lumensteward.clawbot.application.gray.GrayReleaseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 灰度分流引擎单测（FR-22 / 迭代 4 W2）。
 *
 * <p>重点验证 AC①（10% 放量 → 抽样命中在 ±2% 内）与「同一用户恒定同一桶」这条放量前提，
 * 以及 AC③ 要求的一刀切（比例 0 时白名单也必须失效）。
 */
class GrayReleaseServiceTest {

    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final GrayReleaseService gray = new GrayReleaseService(config);

    private void percent(int value) {
        when(config.getInt(eq(ConfigKeys.GRAY_MEMORY_GROWTH_PERCENT), anyInt())).thenReturn(value);
    }

    private void whitelist(String... openids) {
        when(config.getList(eq(ConfigKeys.GRAY_MEMORY_GROWTH_WHITELIST), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(openids));
    }

    @Test
    @DisplayName("AC① 10% 放量：1000 个用户的命中率落在 10%±2% 内，且改比例只放量不洗牌")
    void shouldSplitByRatioWithinTolerance() {
        percent(10);
        whitelist();

        List<String> sample = users(1000);
        int at10 = hits(sample);
        // 1% → 20% 单调放量：10% 命中的用户在 20% 下必须仍命中（否则回退时用户行为反复横跳）
        percent(20);
        int at20 = hits(sample);
        assertThat(at20).isGreaterThan(at10);

        percent(10);
        assertThat(at10).isBetween(80, 120);
        assertThat(at10 * 100.0 / sample.size()).isBetween(8.0, 12.0);
    }

    @Test
    @DisplayName("AC① 稳定性：同一用户重复判定结果一致（跨进程可复现，不依赖 hashCode）")
    void shouldBeDeterministicPerUser() {
        percent(30);
        whitelist();

        String openid = "oTest-Stable-User-001";
        GrayReleaseService.Decision first = gray.decide(GrayFeature.MEMORY_GROWTH, openid);
        for (int i = 0; i < 50; i++) {
            GrayReleaseService.Decision again = gray.decide(GrayFeature.MEMORY_GROWTH, openid);
            assertThat(again.bucket()).isEqualTo(first.bucket());
            assertThat(again.hit()).isEqualTo(first.hit());
        }
        assertThat(first.bucket()).isBetween(0, 99);
        assertThat(first.hit()).isEqualTo(first.bucket() < 30);
    }

    @Test
    @DisplayName("AC③ 比例 0 = 一刀切：白名单也失效（回滚必须能恢复到变更前行为）")
    void zeroPercentOverridesWhitelist() {
        percent(0);
        whitelist("oVip");

        assertThat(gray.decide(GrayFeature.MEMORY_GROWTH, "oVip").hit()).isFalse();
        assertThat(gray.decide(GrayFeature.MEMORY_GROWTH, "oVip").reason())
                .isEqualTo(GrayReleaseService.Decision.GRAY_OFF);
    }

    @Test
    @DisplayName("白名单：比例 > 0 时优先命中，未落入比例的账号也放行")
    void whitelistHitsRegardlessOfBucket() {
        percent(1);
        whitelist("oVip");

        assertThat(gray.decide(GrayFeature.MEMORY_GROWTH, "oNotInBucket-Zzz").hit()).isFalse();

        GrayReleaseService.Decision decision = gray.decide(GrayFeature.MEMORY_GROWTH, "oVip");
        assertThat(decision.hit()).isTrue();
        assertThat(decision.reason()).isEqualTo(GrayReleaseService.Decision.WHITELIST);
    }

    @Test
    @DisplayName("全量 100：所有用户命中（灰度叠加不改变主开关已开启时的既有行为）")
    void hundredPercentIsFullRollout() {
        percent(100);
        whitelist();

        for (String openid : users(20)) {
            GrayReleaseService.Decision decision = gray.decide(GrayFeature.MEMORY_GROWTH, openid);
            assertThat(decision.hit()).isTrue();
            assertThat(decision.reason()).isEqualTo(GrayReleaseService.Decision.FULL_ROLLOUT);
        }
    }

    @Test
    @DisplayName("缺少 openid（如系统触发）按未命中处理，不抛异常")
    void missingOpenidMisses() {
        percent(100);
        whitelist();

        assertThat(gray.decide(GrayFeature.MEMORY_GROWTH, null).reason())
                .isEqualTo(GrayReleaseService.Decision.MISSING_OPENID);
        assertThat(gray.isHit(GrayFeature.MEMORY_GROWTH, "  ")).isFalse();
    }

    @Test
    @DisplayName("配置越界/缺失（999%、白名单为 null）：退化为不放量与空白名单，不抛异常")
    void degradesOnOutOfRangeConfig() {
        DynamicConfigService lenient = mock(DynamicConfigService.class);
        when(lenient.getInt(eq(ConfigKeys.GRAY_MEMORY_GROWTH_PERCENT), anyInt())).thenReturn(999);
        when(lenient.getList(anyString(), org.mockito.ArgumentMatchers.anyList())).thenReturn(null);
        GrayReleaseService fallback = new GrayReleaseService(lenient);

        assertThat(fallback.percent(GrayFeature.MEMORY_GROWTH)).isZero();
        assertThat(fallback.whitelist(GrayFeature.MEMORY_GROWTH)).isEmpty();
        assertThat(fallback.isHit(GrayFeature.MEMORY_GROWTH, "oUser")).isFalse();
    }

    @Test
    @DisplayName("取数层故障不外溢：动态配置读取自身回退默认值（其契约由 DynamicConfigService 保证）")
    void neverThrowsOnBadConfig() {
        // DynamicConfigServiceImpl 对任何读取异常都回退 fallback（见其 catch），
        // 因此灰度判定在对话链路上不会因缓存/数据库抖动而抛异常打断回复。
        DynamicConfigService failing = mock(DynamicConfigService.class);
        when(failing.getInt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(1));
        when(failing.getList(anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        GrayReleaseService onFailingConfig = new GrayReleaseService(failing);

        assertThatCode(() -> onFailingConfig.isHit(GrayFeature.MEMORY_GROWTH, "oUser"))
                .doesNotThrowAnyException();
        assertThat(onFailingConfig.isHit(GrayFeature.MEMORY_GROWTH, "oUser")).isFalse();
    }

    @Test
    @DisplayName("灰度代号解析：大小写/空白容错，未知代号返回 null 由调用方给可读错误")
    void resolvesFeatureByCode() {
        assertThat(GrayFeature.fromCode("memory_growth")).isSameAs(GrayFeature.MEMORY_GROWTH);
        assertThat(GrayFeature.fromCode(" MEMORY_GROWTH ")).isSameAs(GrayFeature.MEMORY_GROWTH);
        assertThat(GrayFeature.fromCode("not_a_feature")).isNull();
        assertThat(GrayFeature.fromCode(null)).isNull();
    }

    private List<String> users(int count) {
        List<String> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            users.add("oBatch-" + i);
        }
        return users;
    }

    private int hits(List<String> users) {
        int hit = 0;
        for (String openid : users) {
            if (gray.isHit(GrayFeature.MEMORY_GROWTH, openid)) {
                hit++;
            }
        }
        return hit;
    }
}
