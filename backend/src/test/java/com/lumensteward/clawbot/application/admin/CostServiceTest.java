package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LlmCallEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.LlmCallMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成本聚合单测（B-4 / W5-c，FR-17 AC① 口径）。
 *
 * <p>验证要点：看板百分比一律由 {@code log_llm_call} 明细 + 生效预算算出（因此可用一条等价 SQL
 * 复算），Redis 计数口径独立并列呈现；分布与排行的分桶、归因不因聚合而失真。
 */
class CostServiceTest {

    private final LlmCallMapper llmCallMapper = mock(LlmCallMapper.class);
    private final CostBudgetService costBudgetService = mock(CostBudgetService.class);

    private final CostService service = new CostService(llmCallMapper, costBudgetService);

    @Test
    @DisplayName("概览：百分比与状态取「明细聚合 / 生效预算」，Redis 计数口径独立并列")
    void overviewUsesDetailAggregation() {
        Deque<Map<String, Object>> rows = new ArrayDeque<>(List.of(
                sumRow(12L, 7000L, 1000L, 8000L), sumRow(300L, 90000L, 9000L, 99000L)));
        when(llmCallMapper.selectMaps(any(Wrapper.class)))
                .thenAnswer(invocation -> List.of(rows.poll()));
        when(costBudgetService.dailyTokenBudget()).thenReturn(10000L);
        // Redis 桶被清空后归零：DB 明细口径不受影响，两个口径同时摆出而非互相掩盖
        when(costBudgetService.dailyUsagePercent()).thenReturn(0);
        when(costBudgetService.isDegraded()).thenReturn(false);

        CostService.Overview overview = service.overview();

        assertThat(overview.todayCalls()).isEqualTo(12L);
        assertThat(overview.todayPromptTokens()).isEqualTo(7000L);
        assertThat(overview.todayCompletionTokens()).isEqualTo(1000L);
        assertThat(overview.todayTokens()).isEqualTo(8000L);
        assertThat(overview.dailyBudget()).isEqualTo(10000L);
        assertThat(overview.usedPercent()).isEqualTo(80);
        assertThat(overview.status()).isEqualTo("WARN");
        assertThat(overview.counterUsagePercent()).isZero();
        assertThat(overview.degraded()).isFalse();
        assertThat(overview.monthTokens()).isEqualTo(99000L);
    }

    @Test
    @DisplayName("概览：明细达预算即判 DEGRADED，百分比封顶 100")
    void overviewMarksDegradedAtBudget() {
        when(llmCallMapper.selectMaps(any(Wrapper.class))).thenAnswer(invocation ->
                List.of(sumRow(20L, 10000L, 2000L, 12000L)));
        when(costBudgetService.dailyTokenBudget()).thenReturn(10000L);
        when(costBudgetService.dailyUsagePercent()).thenReturn(100);
        when(costBudgetService.isDegraded()).thenReturn(true);

        CostService.Overview overview = service.overview();

        assertThat(overview.usedPercent()).isEqualTo(100);
        assertThat(overview.status()).isEqualTo("DEGRADED");
        assertThat(overview.degraded()).isTrue();
    }

    @Test
    @DisplayName("概览：预算为 0（无上限）时百分比记 0、状态 NORMAL")
    void overviewTreatsZeroBudgetAsUnlimited() {
        when(llmCallMapper.selectMaps(any(Wrapper.class))).thenAnswer(invocation ->
                List.of(sumRow(3L, 100L, 50L, 150L)));
        when(costBudgetService.dailyTokenBudget()).thenReturn(0L);

        CostService.Overview overview = service.overview();

        assertThat(overview.usedPercent()).isZero();
        assertThat(overview.status()).isEqualTo("NORMAL");
    }

    @Test
    @DisplayName("趋势：HOUR 走小时桶表达式，其余按天桶")
    void trendSwitchesBucketGranularity() {
        when(llmCallMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

        service.trend(null, null, "HOUR");
        service.trend(null, null, "DAY");

        List<QueryWrapper<LlmCallEntity>> wrappers = capturedWrappers(2);
        assertThat(wrappers.get(0).getSqlSelect()).contains("%Y-%m-%d %H:00");
        assertThat(wrappers.get(1).getSqlSelect()).contains("%Y-%m-%d").doesNotContain("%H:00");
    }

    @Test
    @DisplayName("用途分布：枚举三类固定在前（缺失记 0），库里未知值追加且不静默丢弃")
    void byPurposeKeepsEnumOrderAndAppendsUnknown() {
        when(llmCallMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(
                purposeRow("LEGACY_SUMMARY", 1L, 400L),
                purposeRow("CHAT", 3L, 600L)));

        List<CostService.PurposeSlice> slices = service.byPurpose(null, null);

        assertThat(slices).extracting(CostService.PurposeSlice::purpose)
                .containsExactly("CHAT", "MEMORY_EXTRACT", "INTENT", "LEGACY_SUMMARY");
        assertThat(slices.get(0).tokens()).isEqualTo(600L);
        assertThat(slices.get(0).percent()).isEqualTo(60.0);
        assertThat(slices.get(1).calls()).isZero();
        assertThat(slices.get(1).percent()).isZero();
        assertThat(slices.get(3).tokens()).isEqualTo(400L);
        assertThat(slices.get(3).percent()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("模型分布：百分比分母是区间总量，未指定模型原样为 null")
    void byModelSharesAgainstRangeTotal() {
        Map<String, Object> mockRow = new HashMap<>();
        mockRow.put("provider", "mock");
        mockRow.put("model", null);
        mockRow.put("calls", 6L);
        mockRow.put("tokens", 600L);
        when(llmCallMapper.selectMaps(any(Wrapper.class))).thenReturn(
                List.of(mockRow, modelRow("deepseek", "deepseek-chat", 2L, 400L)),
                List.of(sumRow(8L, 900L, 100L, 1000L)));

        List<CostService.ModelSlice> slices = service.byModel(null, null);

        assertThat(slices).hasSize(2);
        assertThat(slices.get(0).model()).isNull();
        assertThat(slices.get(0).percent()).isEqualTo(60.0);
        assertThat(slices.get(1).percent()).isEqualTo(40.0);
    }

    @Test
    @DisplayName("排行：limit 越界夹紧到 1~50、缺省取默认条数")
    void topUsersClampsLimit() {
        when(llmCallMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

        service.topUsers(null, null, 999);
        service.topUsers(null, null, 0);
        service.topUsers(null, null, null);

        List<QueryWrapper<LlmCallEntity>> wrappers = capturedWrappers(3);
        assertThat(wrappers.get(0).getSqlSegment()).endsWith("LIMIT 50");
        assertThat(wrappers.get(1).getSqlSegment()).endsWith("LIMIT 1");
        assertThat(wrappers.get(2).getSqlSegment()).endsWith("LIMIT 10");
    }

    @Test
    @DisplayName("排行：库里已脱敏的 openid 原样透出不二次打码，NULL 归入未归属")
    void topUsersKeepsMaskedOpenidVerbatim() {
        when(llmCallMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(
                userRow("oabc****wxyz", 3L, 900L),
                userRow(null, 5L, 500L)));

        List<CostService.UserSlice> slices = service.topUsers(null, null, 2);

        // 写入侧已集中脱敏；此处再打码一次会把 oabc****wxyz 削成错误结论
        assertThat(slices.get(0).openid()).isEqualTo("oabc****wxyz");
        assertThat(slices.get(1).openid()).isEqualTo("(未归属)");
        assertThat(slices.get(1).tokens()).isEqualTo(500L);
    }

    @Test
    @DisplayName("明细抽样：token 列缺失回落 0；时间范围只作用于 created_at")
    void recentCallsDefaultsNullTokensAndAppliesRange() {
        LlmCallEntity entity = new LlmCallEntity();
        entity.setId(7L);
        entity.setPurpose("CHAT");
        entity.setProvider("mock");
        entity.setOpenid("oabc****wxyz");
        when(llmCallMapper.selectList(any(Wrapper.class))).thenReturn(List.of(entity));

        List<CostService.CallDetail> details =
                service.recentCalls(LocalDateTime.of(2026, 9, 20, 0, 0), null, 5);
        service.recentCalls(null, null, 5);

        assertThat(details).hasSize(1);
        assertThat(details.get(0).id()).isEqualTo(7L);
        assertThat(details.get(0).promptTokens()).isZero();
        assertThat(details.get(0).totalTokens()).isZero();
        ArgumentCaptor<QueryWrapper<LlmCallEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(llmCallMapper, times(2)).selectList(captor.capture());
        assertThat(captor.getAllValues().get(0).getSqlSegment()).contains("created_at");
        assertThat(captor.getAllValues().get(1).getSqlSegment()).doesNotContain("created_at");
    }

    @SuppressWarnings("unchecked")
    private List<QueryWrapper<LlmCallEntity>> capturedWrappers(int expectedInvocations) {
        ArgumentCaptor<QueryWrapper<LlmCallEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(llmCallMapper, times(expectedInvocations)).selectMaps(captor.capture());
        return captor.getAllValues();
    }

    private static Map<String, Object> sumRow(long calls, long prompt, long completion, long tokens) {
        Map<String, Object> row = new HashMap<>();
        row.put("calls", calls);
        row.put("prompt_tokens", prompt);
        row.put("completion_tokens", completion);
        row.put("tokens", tokens);
        return row;
    }

    private static Map<String, Object> purposeRow(String purpose, long calls, long tokens) {
        Map<String, Object> row = new HashMap<>();
        row.put("purpose", purpose);
        row.put("calls", calls);
        row.put("tokens", tokens);
        return row;
    }

    private static Map<String, Object> modelRow(String provider, String model, long calls, long tokens) {
        Map<String, Object> row = new HashMap<>();
        row.put("provider", provider);
        row.put("model", model);
        row.put("calls", calls);
        row.put("tokens", tokens);
        return row;
    }

    private static Map<String, Object> userRow(String openid, long calls, long tokens) {
        Map<String, Object> row = new HashMap<>();
        row.put("openid", openid);
        row.put("calls", calls);
        row.put("tokens", tokens);
        return row;
    }
}
