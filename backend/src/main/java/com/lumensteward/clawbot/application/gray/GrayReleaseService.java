package com.lumensteward.clawbot.application.gray;

import com.lumensteward.clawbot.application.config.DynamicConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;

/**
 * 灰度分流引擎（FR-22 / 迭代 4 W2）。
 *
 * <p>判定顺序：<b>比例 0 优先关闭一切</b>（含白名单）→ 白名单命中 → 按 openid 哈希比例命中。
 * 比例 0 即「灰度关闭 / 已回滚」，必须是<b>一刀切</b>：若回滚后白名单仍生效，则 FR-22 异常流
 * 「熔断回滚」无法把行为恢复到变更前（AC③），故白名单只在比例 &gt; 0 时参与。
 *
 * <p>分桶用 SHA-256(<code>代号#openid</code>) 而非 {@code String#hashCode()}：
 * 前者跨 JVM 稳定且散列均匀，是「同一用户恒定同一桶」（AC① 抽样 ±2% 的前提）与
 * 「改比例只放量、不洗牌」（单调放量）两条要求的最低实现。盐值取功能代号，
 * 避免同一批用户在不同功能上完全同桶（ correlated rollout）。
 *
 * <p><b>不打断对话链路</b>（灰度判定发生在回复之后的同线程事件监听前）：openid 为空、配置缺失
 * 或值越界一律按未命中处理并告警；读取本身的异常由 {@link DynamicConfigService} 的实现兜底
 * （取值契约：任何读取失败回退调用方给的默认值），本服务不再层层 try/catch。
 */
@Service
public class GrayReleaseService {

    private static final Logger log = LoggerFactory.getLogger(GrayReleaseService.class);

    /** 分桶数：比例单位即百分点。 */
    private static final int BUCKETS = 100;

    private final DynamicConfigService dynamicConfig;

    /**
     * 构造器注入（G-14）。
     *
     * @param dynamicConfig 动态配置源（改值免重启）
     */
    public GrayReleaseService(DynamicConfigService dynamicConfig) {
        this.dynamicConfig = dynamicConfig;
    }

    /**
     * 该用户是否命中灰度。
     *
     * @param feature 灰度功能
     * @param openid  用户标识（原始值，仅参与哈希，不出参不入日志）
     * @return 是否命中
     */
    public boolean isHit(GrayFeature feature, String openid) {
        return decide(feature, openid).hit();
    }

    /**
     * 命中判定明细（供后台预览与自证：同一入参必得同一结果）。
     *
     * @param feature 灰度功能
     * @param openid  用户标识
     * @return 判定结果（含比例、分桶、原因）
     */
    public Decision decide(GrayFeature feature, String openid) {
        int percent = percent(feature);
        if (openid == null || openid.isBlank()) {
            return new Decision(false, percent, -1, Decision.MISSING_OPENID);
        }
        if (percent <= 0) {
            return new Decision(false, percent, -1, Decision.GRAY_OFF);
        }
        if (percent >= BUCKETS) {
            return new Decision(true, percent, -1, Decision.FULL_ROLLOUT);
        }
        int bucket = bucket(feature, openid);
        if (whitelist(feature).contains(openid)) {
            return new Decision(true, percent, bucket, Decision.WHITELIST);
        }
        boolean hit = bucket < percent;
        return new Decision(hit, percent, bucket, hit ? Decision.PERCENT : Decision.NOT_IN_GRAY);
    }

    /**
     * 读取灰度比例（非法/缺失回退 0，即默认不放量）。
     *
     * @param feature 灰度功能
     * @return 0~100
     */
    public int percent(GrayFeature feature) {
        int raw = dynamicConfig.getInt(feature.percentKey(), 0);
        if (raw < 0 || raw > BUCKETS) {
            log.warn("灰度比例非法({}={})，按 0 处理——写入侧应已被 ConfigAdminService 拒绝",
                    feature.percentKey(), raw);
            return 0;
        }
        return raw;
    }

    /**
     * 读取白名单（去空白，永不返回 null）。
     *
     * @param feature 灰度功能
     * @return 白名单 openid 集合
     */
    public Set<String> whitelist(GrayFeature feature) {
        List<String> raw = dynamicConfig.getList(feature.whitelistKey(), List.of());
        return raw == null ? Set.of() : Set.copyOf(raw);
    }

    /**
     * 稳定分桶：SHA-256(代号#openid) 前 4 字节 → 0~99。
     *
     * @param feature 灰度功能（提供盐值）
     * @param openid  用户标识
     * @return 桶号
     */
    private static int bucket(GrayFeature feature, String openid) {
        byte[] digest = sha256(feature.code() + "#" + openid);
        int value = ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16)
                | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
        return Math.floorMod(value, BUCKETS);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必备算法；缺失即环境异常，按 0 桶处理（仍为确定性结果，不影响可复现性）
            log.warn("SHA-256 不可用，灰度分桶退化为 0：err={}", e.getMessage());
            return new byte[4];
        }
    }

    /**
     * 命中判定明细。
     *
     * @param hit     是否命中
     * @param percent 当前比例
     * @param bucket  分桶（未参与计算时为 -1）
     * @param reason  判定原因（见常量）
     */
    public record Decision(boolean hit, int percent, int bucket, String reason) {

        /** 灰度关闭（比例 0，含回滚后）。 */
        public static final String GRAY_OFF = "GRAY_OFF";
        /** 全量放开（比例 100）。 */
        public static final String FULL_ROLLOUT = "FULL_ROLLOUT";
        /** 白名单命中。 */
        public static final String WHITELIST = "WHITELIST";
        /** 比例命中。 */
        public static final String PERCENT = "PERCENT";
        /** 未落入比例。 */
        public static final String NOT_IN_GRAY = "NOT_IN_GRAY";
        /** 缺少用户标识，无法判定。 */
        public static final String MISSING_OPENID = "MISSING_OPENID";
    }
}
