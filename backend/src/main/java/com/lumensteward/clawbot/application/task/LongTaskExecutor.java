package com.lumensteward.clawbot.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.enums.LongTaskStatus;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.task.LongTaskRecord;
import com.lumensteward.clawbot.domain.task.LongTaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 长任务执行器（迭代 4 W15）：有界线程池 + 超时隔离 + 重启续跑 + 投递幂等闩。
 *
 * <p><b>双池结构（为什么不共用一个池做 get(timeout)）：</b>监督线程在 {@code future.get(timeout)}
 * 上等待，若被监督的任务排在同一个池的队列里，池满时内层任务永远得不到线程——监督等待
 * 到超时才释放，任务全体误判超时。故监督池（有界，并发上限=池大小）与工作池
 * （cached daemon，实际并发受监督池上限约束）分离。
 *
 * <p><b>续跑语义（W15 交付判据）：</b>启动时（ApplicationRunner，排在口令播种之后）把
 * RUNNING 残局重置回 PENDING 再入队；「执行成功但投递前崩溃」的任务由
 * {@link #deliverSucceededUndelivered()} 只补投不再执行——投递由
 * {@code delivered_at IS NULL} 幂等闩保证至多一次。另有 60s 周期扫描兜底 PENDING 积压。
 */
@Component
public class LongTaskExecutor implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LongTaskExecutor.class);

    /** 单次扫描最多入队的任务数（防一次性积压淹没有界池）。 */
    private static final int SWEEP_LIMIT = 100;

    private final LongTaskStore store;
    private final WechatMessageService wechatMessageService;
    private final DynamicConfigService dynamicConfig;
    private final Map<String, LongTaskHandler> handlers = new LinkedHashMap<>();

    private final ThreadPoolExecutor supervisorPool;
    private final ExecutorService workerPool;

    /**
     * Spring 装配用构造器（G-14）：收集全部 handler（类型重复阻止启动），
     * 池大小经 {@code longtask.pool-size} 配置（默认 2，重启生效）。
     */
    @Autowired
    public LongTaskExecutor(LongTaskStore store, WechatMessageService wechatMessageService,
                            DynamicConfigService dynamicConfig, List<LongTaskHandler> handlerList) {
        this.store = store;
        this.wechatMessageService = wechatMessageService;
        this.dynamicConfig = dynamicConfig;
        for (LongTaskHandler handler : handlerList) {
            LongTaskHandler previous = handlers.put(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("长任务类型重复注册: " + handler.type()
                        + "（" + previous.getClass().getSimpleName() + " / "
                        + handler.getClass().getSimpleName() + "）");
            }
        }
        int poolSize = dynamicConfig == null ? 2
                : Math.max(1, dynamicConfig.getInt(ConfigKeys.LONGTASK_POOL_SIZE, 2));
        this.supervisorPool = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(64), r -> {
            Thread thread = new Thread(r, "clawbot-long-task");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        this.workerPool = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "clawbot-long-task-work");
            thread.setDaemon(true);
            return thread;
        });
        log.info("长任务执行器就绪：handler 类型={}，池大小={}", handlers.keySet(), poolSize);
    }

    /** 是否支持该任务类型（提交侧校验用）。 */
    public boolean supports(String type) {
        return type != null && handlers.containsKey(type);
    }

    /** 提交新任务到监督池（任务须已落库为 PENDING）。 */
    public void dispatch(long taskId) {
        supervisorPool.execute(() -> supervise(taskId));
    }

    /** 启动续跑：RUNNING 残局重置 → PENDING 入队 → 补投成功未投递的残局。 */
    @Override
    public void run(ApplicationArguments args) {
        store.requeueRunning();
        sweep();
    }

    /** 周期兜底：PENDING 积压入队 + 成功未投递补投（幂等闩保证重复调用无害）。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void sweep() {
        try {
            for (LongTaskRecord task : store.findPending(SWEEP_LIMIT)) {
                dispatch(task.id());
            }
            deliverSucceededUndelivered();
        } catch (RuntimeException e) {
            log.warn("长任务扫描异常（下一轮重试）: {}", e.getMessage());
        }
    }

    /** 补投「执行成功但投递前崩溃」的残局：只投递，不再执行（幂等闩至多一次）。 */
    public void deliverSucceededUndelivered() {
        for (LongTaskRecord task : store.findSucceededUndelivered(SWEEP_LIMIT)) {
            deliver(task, describeResult(task));
        }
    }

    /** 单任务监督：抢占 → 执行（带超时）→ 落终态 → 幂等投递。 */
    private void supervise(long taskId) {
        try {
            if (!store.claim(taskId)) {
                return;
            }
            LongTaskRecord task = store.find(taskId).orElse(null);
            if (task == null || task.status() != LongTaskStatus.RUNNING) {
                return;
            }
            LongTaskHandler handler = handlers.get(task.taskType());
            if (handler == null) {
                fail(task, "NO_HANDLER", "任务类型无处理器");
                return;
            }
            long timeoutSeconds = timeoutSeconds();
            Future<JsonNode> future = workerPool.submit(
                    () -> handler.execute(parsePayload(task.payloadJson())));
            try {
                JsonNode result = future.get(timeoutSeconds, TimeUnit.SECONDS);
                store.completeSuccess(taskId, JsonUtils.toJson(result));
                log.info("长任务完成 id={} type={} openid={} 尝试次数={}", taskId, task.taskType(),
                        MaskUtils.openid(task.openid()), task.attempts());
                deliver(task, handler.describe(result));
            } catch (TimeoutException e) {
                future.cancel(true);
                fail(task, "TIMEOUT", "执行超过 " + timeoutSeconds + " 秒被终止");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                fail(task, "HANDLER_FAILED", "执行被中断");
            } catch (ExecutionException e) {
                fail(task, "HANDLER_FAILED", String.valueOf(e.getCause()));
            }
        } catch (RuntimeException e) {
            log.error("长任务监督异常 id={}（下一轮扫描视残局状态处置）: {}", taskId, e.getMessage());
        }
    }

    private void fail(LongTaskRecord task, String errorType, String reason) {
        store.completeFailure(task.id(), errorType);
        log.warn("长任务失败 id={} type={} error_type={} 原因={}", task.id(), task.taskType(),
                errorType, reason);
        deliver(task, "很抱歉，你之前交代的任务（" + task.taskType() + "）没有跑成（" + errorType + "），"
                + "可以直接再让我试一次。");
    }

    /** 幂等投递：delivered_at 闩抢到的一方才真正外发（至多一次，W15 交付判据）。 */
    private void deliver(LongTaskRecord task, String text) {
        if (!store.markDelivered(task.id())) {
            return;
        }
        try {
            wechatMessageService.pushAsync(task.openid(), text);
        } catch (RuntimeException e) {
            log.warn("长任务结果投递异常 id={}（闩已置位，消息通道自身可查）: {}",
                    task.id(), e.getMessage());
        }
    }

    private String describeResult(LongTaskRecord task) {
        LongTaskHandler handler = handlers.get(task.taskType());
        if (handler == null) {
            return "你之前交代的任务（" + task.taskType() + "）有结果了。";
        }
        try {
            return handler.describe(parsePayloadAsResult(task.resultJson()));
        } catch (RuntimeException e) {
            return "你之前交代的任务（" + task.taskType() + "）有结果了。";
        }
    }

    /** 解析 JSON；格式非法按空对象处理（入参为管理员提交，写侧已做 JSON 校验，此处兜底）。 */
    private static JsonNode parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return JsonUtils.mapper().createObjectNode();
        }
        try {
            return JsonUtils.mapper().readTree(payloadJson);
        } catch (Exception e) {
            return JsonUtils.mapper().createObjectNode();
        }
    }

    private static JsonNode parsePayloadAsResult(String resultJson) {
        return parsePayload(resultJson);
    }

    private long timeoutSeconds() {
        return dynamicConfig == null ? 60
                : Math.max(1, dynamicConfig.getInt(ConfigKeys.LONGTASK_TIMEOUT_SECONDS, 60));
    }
}
