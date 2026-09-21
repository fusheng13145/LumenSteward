package com.lumensteward.clawbot.application.gray;

import com.lumensteward.clawbot.application.admin.ConfigAdminService;
import com.lumensteward.clawbot.application.admin.ConfigAdminService.ConfigItem;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 灰度一键回滚（FR-22 / W2；BR-31「无回滚方案的变更不得上线」的落地出口）。
 *
 * <p>回滚动作本身<b>复用 {@link ConfigAdminService}</b>：于是天然带上 FR-18 的三件事——
 * 值校验、缓存失效（下一次读取即生效，满足 AC②「60s 内回滚」）、逐项 {@code CONFIG_UPDATE} 审计。
 * 此处额外补一行 {@code reg_type=GRAY} 汇总审计，让「因何回滚」与「改了哪些键」在同一行可比对。
 *
 * <p>白名单<b>不在回滚范围内</b>：比例置 0 后白名单已不生效（见 {@link GrayReleaseService}），
 * 保留名单值是为了事后仍能看出放量计划，而不是把计划一起抹掉。
 */
@Service
public class GrayRollbackService {

    private static final Logger log = LoggerFactory.getLogger(GrayRollbackService.class);

    private final ConfigAdminService configAdminService;
    private final GrayReleaseService grayRelease;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param configAdminService 配置写服务（校验 + 热生效 + 留痕）
     * @param grayRelease        灰度读取（取当前比例）
     * @param auditLogService    审计写服务
     */
    public GrayRollbackService(ConfigAdminService configAdminService,
                               GrayReleaseService grayRelease,
                               AuditLogService auditLogService) {
        this.configAdminService = configAdminService;
        this.grayRelease = grayRelease;
        this.auditLogService = auditLogService;
    }

    /**
     * 把全部在放量的灰度功能比例置 0。
     *
     * @param trigger 回滚触发说明（如「熔断自动回滚：错误率 45% > 30%」或运维填写的原因）
     * @param adminId 操作人；系统自动回滚为 {@code null}（{@code log_audit.admin_id} 允许 NULL）
     * @param ip      来源 IP；系统触发为 {@code null}
     * @return 回滚结果
     */
    public RollbackResult rollbackAll(String trigger, Long adminId, String ip) {
        List<ConfigItem> items = new ArrayList<>();
        List<String> before = new ArrayList<>();
        for (GrayFeature feature : GrayFeature.values()) {
            int percent = grayRelease.percent(feature);
            if (percent > 0) {
                items.add(new ConfigItem(feature.percentKey(), "0"));
                before.add(feature.code() + "=" + percent);
            }
        }
        if (items.isEmpty()) {
            log.info("灰度回滚：无在放量功能（比例本就全为 0），跳过写入");
            return new RollbackResult(0, List.of());
        }
        configAdminService.update(items, "灰度回滚：" + trigger, adminId, ip);
        audit(adminId, String.join(",", before), trigger, ip);
        log.warn("灰度已回滚（比例置 0，含白名单一并失效）: features={} trigger={} adminId={}",
                before, trigger, adminId);
        return new RollbackResult(items.size(), List.copyOf(before));
    }

    private void audit(Long adminId, String before, String trigger, String ip) {
        try {
            auditLogService.record(adminId, "GRAY", "ROLLBACK", "gray.*.percent",
                    before, "percent=0", trigger, ip, 1);
        } catch (RuntimeException e) {
            log.warn("灰度回滚审计写失败: trigger={} err={}", trigger, e.getMessage());
        }
    }

    /**
     * 回滚结果。
     *
     * @param rolledBack  被置 0 的灰度功能数
     * @param fromPercent 变更明细（{@code 代号=原比例}）
     */
    public record RollbackResult(int rolledBack, List<String> fromPercent) {
    }
}
