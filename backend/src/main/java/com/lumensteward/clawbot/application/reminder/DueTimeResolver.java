package com.lumensteward.clawbot.application.reminder;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 到期时间解析器（迭代 4 W7/W8）：把抽取器给出的 due 表达定格为绝对时间。
 *
 * <p>接受两种<b>可机读</b>表达（登记即定格，避免「明天」在投递日漂移）：
 * <ul>
 *   <li><b>相对</b>：{@code +PT30M / +PT2H / +PT1D}（IST 风格时长，分钟/小时/天）；</li>
 *   <li><b>ISO-8601 本地日期时间</b>：{@code 2026-11-07T15:00} 或带秒；过期时刻按已到期处理
 *       （登记即到期，下轮扫描即投——宁早勿丢）。</li>
 * </ul>
 * 其余表达（含自然语言「明天下午」）一律拒绝——抽取提示词已要求无法给出机读时间就不输出承诺；
 * 拒绝比猜一个时间诚实（BR-09）。
 */
@Component
public class DueTimeResolver {

    /** 相对表达：+PT[n]M / +PT[n]H / +PT[n]D（分钟 / 小时 / 天）。 */
    private static final Pattern RELATIVE = Pattern.compile(
            "^\\+P(?:(\\d{1,4})D)?T?(?:(\\d{1,4})H)?(?:(\\d{1,4})M)?$");

    /** ISO 本地日期时间（秒可选：2026-11-07T15:00 与 2026-11-07T15:00:00 均可）。 */
    private static final DateTimeFormatter ISO_DATETIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 投递调度所在时区（单拥有方私有部署，取系统默认时区）。 */
    private final ZoneId zone;

    /** 构造器（G-14）。 */
    public DueTimeResolver() {
        this(ZoneId.systemDefault());
    }

    /** 测试注入时区用。 */
    DueTimeResolver(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * 解析 due 表达。
     *
     * @param dueSpec 抽取器给出的到期表达
     * @return 定格的绝对时刻；不可解析 / 非法 / 相对为零返回 empty
     */
    public Optional<LocalDateTime> resolve(String dueSpec) {
        if (dueSpec == null || dueSpec.isBlank()) {
            return Optional.empty();
        }
        String spec = dueSpec.trim();
        LocalDateTime now = LocalDateTime.now(zone);
        if (spec.startsWith("+")) {
            return resolveRelative(spec, now);
        }
        return resolveIso(spec, now);
    }

    private Optional<LocalDateTime> resolveRelative(String spec, LocalDateTime now) {
        Matcher matcher = RELATIVE.matcher(spec);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            long days = group(matcher, 1);
            long hours = group(matcher, 2);
            long minutes = group(matcher, 3);
            Duration duration = Duration.ofDays(days).plusHours(hours).plusMinutes(minutes);
            if (duration.isZero() || duration.isNegative()) {
                return Optional.empty();
            }
            return Optional.of(now.plus(duration));
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
    }

    private Optional<LocalDateTime> resolveIso(String spec, LocalDateTime now) {
        try {
            LocalDateTime parsed = LocalDateTime.parse(spec, ISO_DATETIME);
            // 过期时刻按已到期处理（宁早勿丢），但仍须落在合理区间（不早于 2015 年，防手滑年份）
            if (parsed.getYear() < 2015 || parsed.getYear() > now.getYear() + 5) {
                return Optional.empty();
            }
            return Optional.of(parsed);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static long group(Matcher matcher, int groupIndex) {
        String value = matcher.group(groupIndex);
        return value == null ? 0 : Long.parseLong(value);
    }
}
