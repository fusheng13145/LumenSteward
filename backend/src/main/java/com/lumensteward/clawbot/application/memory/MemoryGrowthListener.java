package com.lumensteward.clawbot.application.memory;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.gray.GrayFeature;
import com.lumensteward.clawbot.application.gray.GrayReleaseService;
import com.lumensteward.clawbot.application.reminder.DueTimeResolver;
import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.memory.MemoryStore;
import com.lumensteward.clawbot.domain.memory.MemoryWrite;
import com.lumensteward.clawbot.domain.reminder.CommitmentStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 个人状态库生长监听器（迭代 4 W6：活动 → 抽取 → 带溯源落库）。
 *
 * <p><b>为什么在监听器里自建线程池：</b>抽取是一次额外的模型调用（约数百毫秒到数秒），
 * 若同线程执行会把用户可感知的回复时延翻倍；而 {@code @EventListener} 默认与发布方同线程，
 * 本仓库又未启用 {@code @Async}（无 {@code @EnableAsync}，避免隐性改变全局代理行为）。
 * 故此处持有<b>自有有界线程池</b>：单线程串行 + 有界队列，队列满即<b>丢弃并计数</b>
 * ——生长是可牺牲能力，绝不能反压主链路或无界堆积内存。
 *
 * <p>闸门（{@code memory.growth.enabled}）默认关闭：该能力每条消息多一次模型调用，
 * 属<b>预留未启用</b>项（G-32/G-33），须由管理者显式开启；开关经动态配置读取，改值免重启。
 * 开启后再过灰度闸门（{@code gray.memory_growth.*}，FR-22）：按 openid 稳定分桶比例 + 白名单
 * 逐用户放量，熔断回滚置 0 即全量停止生长（默认比例 100，行为与灰度上线前一致）。
 *
 * <p>BR-07：候选事实的 openid 来自当轮消息，写入即落在该用户名下，无跨用户路径。
 */
@Component
public class MemoryGrowthListener {

    private static final Logger log = LoggerFactory.getLogger(MemoryGrowthListener.class);

    /** 队列容量：约 10 轮对话的缓冲，超出即丢弃（后台生长允许丢，不允许堆积）。 */
    private static final int QUEUE_CAPACITY = 64;

    /** 配置缺省时的单次抽取条数上限。 */
    private static final int DEFAULT_MAX_ITEMS = 5;

    private final MemoryExtractor extractor;
    private final MemoryStore memoryStore;
    private final CommitmentStore commitmentStore;
    private final DueTimeResolver dueTimeResolver;
    private final DynamicConfigService dynamicConfig;
    private final GrayReleaseService grayRelease;

    /** 因队列满被丢弃的轮次数（可观测，避免「静默失效」重演 D7）。 */
    private final AtomicLong dropped = new AtomicLong();

    private final ThreadPoolExecutor executor;

    /**
     * Spring 装配用构造器（G-14）：承诺分流依赖齐备（W7/W8 形态）。
     *
     * @param extractor       记忆抽取器
     * @param memoryStore     状态库存储端口
     * @param commitmentStore 承诺存储端口（COMMITMENT 分流目标，W7/W8）
     * @param dueTimeResolver 到期时间解析器（登记时定格相对表达）
     * @param dynamicConfig   动态配置源
     * @param grayRelease     灰度分流（FR-22：主开关开启后按比例/白名单逐用户放量）
     */
    public MemoryGrowthListener(MemoryExtractor extractor, MemoryStore memoryStore,
                               CommitmentStore commitmentStore, DueTimeResolver dueTimeResolver,
                               DynamicConfigService dynamicConfig, GrayReleaseService grayRelease) {
        this.extractor = extractor;
        this.memoryStore = memoryStore;
        this.commitmentStore = commitmentStore;
        this.dueTimeResolver = dueTimeResolver;
        this.dynamicConfig = dynamicConfig;
        this.grayRelease = grayRelease;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "clawbot-memory-grow");
                    thread.setDaemon(true);
                    return thread;
                },
                (runnable, pool) -> {
                    long total = dropped.incrementAndGet();
                    log.warn("状态库生长任务被丢弃（队列已满或线程池已关闭，累计丢弃={}）", total);
                });
    }

    /**
     * 接收一轮对话活动并按需生长记忆。
     *
     * @param notice 对话活动
     */
    @EventListener
    public void onConversationTurn(MemoryGrowthNotice notice) {
        if (notice == null || !notice.worthExtracting()) {
            return;
        }
        if (!enabled(notice.openid())) {
            return;
        }
        int maxItems = maxItems();
        executor.execute(() -> grow(notice, maxItems));
    }

    private void grow(MemoryGrowthNotice notice, int maxItems) {
        try {
            List<MemoryWrite> candidates = extractor.extract(notice, maxItems);
            if (candidates.isEmpty()) {
                return;
            }
            int created = 0;
            int superseded = 0;
            int reinforced = 0;
            int commitments = 0;
            int skipped = 0;
            for (MemoryWrite write : candidates) {
                // W7/W8：承诺类分流至 biz_commitment（不入状态库，避免被召回当「事实」注入）
                if (write.kind() == MemoryKind.COMMITMENT) {
                    if (registerCommitment(notice, write)) {
                        commitments++;
                    } else {
                        skipped++;
                    }
                    continue;
                }
                MemoryStore.WriteOutcome outcome = memoryStore.upsert(write);
                if (outcome == null) {
                    continue;
                }
                switch (outcome) {
                    case CREATED -> created++;
                    case SUPERSEDED -> superseded++;
                    case REINFORCED -> reinforced++;
                }
            }
            log.info("状态库生长完成 openid={} 候选={} 新增={} 覆盖={} 强化={} 承诺={} 丢弃候选={} 入队丢弃={}",
                    MaskUtils.openid(notice.openid()), candidates.size(),
                    created, superseded, reinforced, commitments, skipped, dropped.get());
        } catch (RuntimeException e) {
            log.warn("状态库生长失败（忽略，不影响已发出的回复）: openid={} err={}",
                    MaskUtils.openid(notice.openid()), e.getMessage());
        }
    }

    /**
     * 承诺登记：due 可机读才登记（解析失败跳过并 warn——宁不登记也不猜时间，BR-09）；
     * 无 due 的承诺项（真实模型偶发漏带）同样跳过。
     *
     * @return 是否登记成功
     */
    private boolean registerCommitment(MemoryGrowthNotice notice, MemoryWrite write) {
        var dueAt = dueTimeResolver.resolve(write.dueSpec());
        if (dueAt.isEmpty()) {
            log.warn("承诺缺少可机读到期时间，跳过登记 openid={} name={} due={}",
                    MaskUtils.openid(notice.openid()), write.name(), write.dueSpec());
            return false;
        }
        long id = commitmentStore.register(notice.openid(), write.name() + "：" + write.content(),
                dueAt.get(), notice.sessionId(), notice.traceId());
        if (id <= 0) {
            log.warn("承诺登记未生效（入参非法） openid={} name={}",
                    MaskUtils.openid(notice.openid()), write.name());
            return false;
        }
        log.info("承诺已登记 id={} openid={} 到期={} 内容={}",
                id, MaskUtils.openid(notice.openid()), dueAt.get(), write.content());
        return true;
    }

    /**
     * 闸门：主开关开启<b>且</b>该用户命中灰度（FR-22）。
     *
     * <p>比例默认 100，故灰度上线不改变既有行为；熔断回滚置 0 后本方法对该用户恒为 false。
     *
     * @param openid 本轮用户标识（仅参与哈希分桶，不出参不入日志）
     * @return 是否执行生长
     */
    private boolean enabled(String openid) {
        return dynamicConfig != null
                && dynamicConfig.getBoolean(ConfigKeys.MEMORY_GROWTH_ENABLED, false)
                && grayRelease.isHit(GrayFeature.MEMORY_GROWTH, openid);
    }

    private int maxItems() {
        return dynamicConfig == null
                ? DEFAULT_MAX_ITEMS : dynamicConfig.getInt(ConfigKeys.MEMORY_GROWTH_MAX_ITEMS, DEFAULT_MAX_ITEMS);
    }

    /** 关闭后台线程池：在途任务至多一轮，队列中的轮次按丢弃处理（数据可再生，不阻塞退出）。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 已丢弃轮次计数（测试与排障用）。 */
    public long droppedCount() {
        return dropped.get();
    }
}
