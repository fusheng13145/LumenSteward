package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lumensteward.clawbot.application.ratelimit.BudgetEvaluator;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
import com.lumensteward.clawbot.common.enums.LlmCallPurpose;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LlmCallEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.LlmCallMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成本与配额治理聚合服务（B-4 / 迭代 4 W5，FR-17 只读看板）。
 *
 * <p><b>唯一数据源是 {@code log_llm_call} 明细</b>：所有百分比的分母都取
 * {@link CostBudgetService#dailyTokenBudget()}（与降级判定同一个生效值），分子取本表聚合，
 * 因此看板上的每个数字都能用一条等价 SQL 复算——这正是 FR-17 AC① 的取证方式。
 *
 * <p><b>双口径并列而非取一</b>：{@code counterUsagePercent} 读 Redis 计数桶（降级判定实际用的
 * 那个值），{@code usedPercent} 读 DB 明细。两者正常情况下相等；Redis 被清空/换实例时前者归零
 * 而后者不变，把两个都摆出来，口径漂移才是<b>可见</b>的（不隐藏不一致）。
 *
 * <p>依赖铁律（NFR-MA-03）：本服务只读，不落库、不改配置；聚合走 {@code selectMaps} 单表分组，
 * 时间范围统一作用在 {@code created_at}（该列有索引，见 V1.0.15）。
 */
@Service
public class CostService {

    private static final String BUCKET_DAY = "DATE_FORMAT(created_at, '%Y-%m-%d')";
    private static final String BUCKET_HOUR = "DATE_FORMAT(created_at, '%Y-%m-%d %H:00')";

    /** 未归属桶标签：无用户标识的调用（意图分类）在排行里单列，不并入任何真实用户。 */
    static final String UNATTRIBUTED = "(未归属)";

    /** top-users 默认返回条数。 */
    static final int DEFAULT_TOP_USERS = 10;

    /** top-users 上限（防一次拉走全表分组）。 */
    static final int MAX_TOP_USERS = 50;

    private final LlmCallMapper llmCallMapper;
    private final CostBudgetService costBudgetService;

    /**
     * 构造器注入（G-14）。
     *
     * @param llmCallMapper     LLM 调用计量 Mapper
     * @param costBudgetService 成本预算服务（提供生效预算与降级标志）
     */
    public CostService(LlmCallMapper llmCallMapper, CostBudgetService costBudgetService) {
        this.llmCallMapper = llmCallMapper;
        this.costBudgetService = costBudgetService;
    }

    /**
     * 概览 KPI：今日消耗 / 生效预算 / 两套百分比 / 降级状态。
     *
     * @return 概览
     */
    public Overview overview() {
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        Map<String, Object> today = sumRange(dayStart, null);
        long todayCalls = toLong(today.get("calls"));
        long todayTokens = toLong(today.get("tokens"));
        long todayPrompt = toLong(today.get("prompt_tokens"));
        long todayCompletion = toLong(today.get("completion_tokens"));
        long monthTokens = toLong(sumRange(LocalDate.now().withDayOfMonth(1).atStartOfDay(), null).get("tokens"));
        long budget = costBudgetService.dailyTokenBudget();
        return new Overview(todayCalls, todayPrompt, todayCompletion, todayTokens, budget,
                BudgetEvaluator.percent(todayTokens, budget),
                BudgetEvaluator.evaluate(todayTokens, budget).name(),
                costBudgetService.dailyUsagePercent(),
                costBudgetService.isDegraded(), monthTokens);
    }

    /**
     * token 消耗趋势（折线）。
     *
     * @param start       下界（可空）
     * @param end         上界（可空）
     * @param granularity {@code HOUR} 为小时桶，其余按天桶
     * @return 按时间正序的趋势点
     */
    public List<TrendPoint> trend(LocalDateTime start, LocalDateTime end, String granularity) {
        String bucketExpr = "HOUR".equalsIgnoreCase(granularity) ? BUCKET_HOUR : BUCKET_DAY;
        QueryWrapper<LlmCallEntity> wrapper = new QueryWrapper<>();
        wrapper.select(bucketExpr + " AS bucket",
                        "COUNT(*) AS calls",
                        "SUM(prompt_tokens) AS prompt_tokens",
                        "SUM(completion_tokens) AS completion_tokens",
                        "SUM(total_tokens) AS tokens")
                .groupBy(bucketExpr)
                .orderByAsc("bucket");
        applyTimeRange(wrapper, start, end);
        List<TrendPoint> points = new ArrayList<>();
        for (Map<String, Object> row : llmCallMapper.selectMaps(wrapper)) {
            points.add(new TrendPoint(toText(row.get("bucket")), toLong(row.get("calls")),
                    toLong(row.get("prompt_tokens")), toLong(row.get("completion_tokens")),
                    toLong(row.get("tokens"))));
        }
        return points;
    }

    /**
     * 按用途分布（饼图）：固定三类在前（缺失记 0），库里出现的未知值追加在后。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 分布列表
     */
    public List<PurposeSlice> byPurpose(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<LlmCallEntity> wrapper = new QueryWrapper<>();
        wrapper.select("purpose", "COUNT(*) AS calls", "SUM(total_tokens) AS tokens")
                .groupBy("purpose");
        applyTimeRange(wrapper, start, end);
        Map<String, long[]> byPurpose = new LinkedHashMap<>();
        for (Map<String, Object> row : llmCallMapper.selectMaps(wrapper)) {
            byPurpose.put(toText(row.get("purpose")),
                    new long[] {toLong(row.get("calls")), toLong(row.get("tokens"))});
        }
        long totalTokens = byPurpose.values().stream().mapToLong(item -> item[1]).sum();
        List<PurposeSlice> slices = new ArrayList<>();
        for (LlmCallPurpose purpose : LlmCallPurpose.values()) {
            long[] pair = byPurpose.remove(purpose.name());
            slices.add(new PurposeSlice(purpose.name(), pair == null ? 0L : pair[0],
                    pair == null ? 0L : pair[1], share(pair == null ? 0L : pair[1], totalTokens)));
        }
        // 库里存在枚举未覆盖的历史值（例如枚举调整后）：如实追加，不静默丢弃
        byPurpose.forEach((purpose, pair) -> slices.add(
                new PurposeSlice(purpose, pair[0], pair[1], share(pair[1], totalTokens))));
        return slices;
    }

    /**
     * 按供应商/模型分布（柱状）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 按 token 降序
     */
    public List<ModelSlice> byModel(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<LlmCallEntity> wrapper = new QueryWrapper<>();
        wrapper.select("provider", "model", "COUNT(*) AS calls", "SUM(total_tokens) AS tokens")
                .groupBy("provider", "model")
                .orderByDesc("tokens");
        applyTimeRange(wrapper, start, end);
        List<Map<String, Object>> rows = llmCallMapper.selectMaps(wrapper);
        long totalTokens = toLong(sumRange(start, end).get("tokens"));
        List<ModelSlice> slices = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long tokens = toLong(row.get("tokens"));
            slices.add(new ModelSlice(toText(row.get("provider")), toText(row.get("model")),
                    toLong(row.get("calls")), tokens, share(tokens, totalTokens)));
        }
        return slices;
    }

    /**
     * 消耗排行 top-users（脱敏标识，BR-21）。
     *
     * <p>{@code openid} 列存的<b>已是脱敏形态</b>（写入侧集中脱敏），故此处原样透出、
     * <b>不再二次打码</b>（二次打码会把 {@code oabc****wxyz} 再削一次，得到错误结论）；
     * NULL 归入 {@link #UNATTRIBUTED}。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @param limit 返回条数（1~50，越界夹紧）
     * @return 按 token 降序
     */
    public List<UserSlice> topUsers(LocalDateTime start, LocalDateTime end, Integer limit) {
        int size = limit == null ? DEFAULT_TOP_USERS : Math.min(MAX_TOP_USERS, Math.max(1, limit));
        QueryWrapper<LlmCallEntity> wrapper = new QueryWrapper<>();
        wrapper.select("openid", "COUNT(*) AS calls", "SUM(total_tokens) AS tokens")
                .groupBy("openid")
                .orderByDesc("tokens")
                .last("LIMIT " + size);
        applyTimeRange(wrapper, start, end);
        List<UserSlice> slices = new ArrayList<>();
        for (Map<String, Object> row : llmCallMapper.selectMaps(wrapper)) {
            String openid = toText(row.get("openid"));
            slices.add(new UserSlice(openid == null || openid.isBlank() ? UNATTRIBUTED : openid,
                    toLong(row.get("calls")), toLong(row.get("tokens"))));
        }
        return slices;
    }

    /**
     * 明细抽样（FR-17 AC① 取证用：看板数字须能逐行对上）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @param limit 返回条数（1~50，越界夹紧）
     * @return 按时间倒序的明细行
     */
    public List<CallDetail> recentCalls(LocalDateTime start, LocalDateTime end, Integer limit) {
        int size = limit == null ? DEFAULT_TOP_USERS : Math.min(MAX_TOP_USERS, Math.max(1, limit));
        QueryWrapper<LlmCallEntity> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("id").last("LIMIT " + size);
        applyTimeRange(wrapper, start, end);
        List<CallDetail> details = new ArrayList<>();
        for (LlmCallEntity entity : llmCallMapper.selectList(wrapper)) {
            details.add(new CallDetail(entity.getId(), entity.getPurpose(), entity.getProvider(),
                    entity.getModel(), entity.getOpenid(), entity.getSessionId(), entity.getTraceId(),
                    nullToZero(entity.getPromptTokens()), nullToZero(entity.getCompletionTokens()),
                    nullToZero(entity.getTotalTokens()), entity.getCreatedAt()));
        }
        return details;
    }

    private Map<String, Object> sumRange(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<LlmCallEntity> wrapper = new QueryWrapper<>();
        wrapper.select("COUNT(*) AS calls",
                        "COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens",
                        "COALESCE(SUM(completion_tokens), 0) AS completion_tokens",
                        "COALESCE(SUM(total_tokens), 0) AS tokens");
        applyTimeRange(wrapper, start, end);
        List<Map<String, Object>> rows = llmCallMapper.selectMaps(wrapper);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private static void applyTimeRange(QueryWrapper<LlmCallEntity> wrapper,
                                       LocalDateTime start, LocalDateTime end) {
        if (start != null) {
            wrapper.ge("created_at", start);
        }
        if (end != null) {
            wrapper.le("created_at", end);
        }
    }

    /** 百分比（0–100，一位小数）；总量为 0 时返回 0。 */
    private static double share(long part, long total) {
        if (total <= 0L) {
            return 0.0;
        }
        return Math.round((double) part * 1000.0 / total) / 10.0;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String toText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 概览 KPI。
     *
     * @param todayCalls        今日调用次数（DB 明细）
     * @param todayPromptTokens 今日输入 token
     * @param todayCompletionTokens 今日输出 token
     * @param todayTokens       今日合计 token
     * @param dailyBudget       当前生效日预算（与降级判定同源）
     * @param usedPercent       今日已用占预算百分比（DB 明细口径，0–100）
     * @param status            预算状态（NORMAL / WARN / DEGRADED，按 DB 明细判定）
     * @param counterUsagePercent Redis 计数桶口径的百分比（与降级判定实际同源）
     * @param degraded          是否已进入降级（按今日计数桶 / 当前生效预算实时判定，编排器实际行为）
     * @param monthTokens       本月累计 token
     */
    public record Overview(long todayCalls, long todayPromptTokens, long todayCompletionTokens,
                           long todayTokens, long dailyBudget, int usedPercent, String status,
                           int counterUsagePercent, boolean degraded, long monthTokens) {
    }

    /**
     * 趋势点。
     *
     * @param bucket             时间桶（{@code yyyy-MM-dd} 或 {@code yyyy-MM-dd HH:00}）
     * @param calls              调用次数
     * @param promptTokens       输入 token
     * @param completionTokens   输出 token
     * @param tokens             合计 token
     */
    public record TrendPoint(String bucket, long calls, long promptTokens, long completionTokens,
                             long tokens) {
    }

    /**
     * 用途分布切片。
     *
     * @param purpose   用途（CHAT / MEMORY_EXTRACT / INTENT）
     * @param calls     调用次数
     * @param tokens    合计 token
     * @param percent   占区间总 token 的百分比（0–100，一位小数）
     */
    public record PurposeSlice(String purpose, long calls, long tokens, double percent) {
    }

    /**
     * 模型分布切片。
     *
     * @param provider 供应商标识
     * @param model    模型名（未显式指定时为 null，前端显示为「供应商默认」）
     * @param calls    调用次数
     * @param tokens   合计 token
     * @param percent  占区间总 token 的百分比
     */
    public record ModelSlice(String provider, String model, long calls, long tokens, double percent) {
    }

    /**
     * 用户消耗排行项。
     *
     * @param openid 脱敏后的用户标识；无归属者为 {@link #UNATTRIBUTED}
     * @param calls  调用次数
     * @param tokens 合计 token
     */
    public record UserSlice(String openid, long calls, long tokens) {
    }

    /**
     * 明细行（抽样取证）。
     *
     * @param id               主键
     * @param purpose          用途
     * @param provider         供应商
     * @param model            模型名（可空）
     * @param openid           脱敏用户标识（可空）
     * @param sessionId        会话 id（可空）
     * @param traceId          链路标识（可空）
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param totalTokens      合计 token
     * @param createdAt        调用时间
     */
    public record CallDetail(Long id, String purpose, String provider, String model, String openid,
                             Long sessionId, String traceId, int promptTokens, int completionTokens,
                             int totalTokens, LocalDateTime createdAt) {
    }
}
