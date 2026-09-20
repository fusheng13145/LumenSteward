package com.lumensteward.clawbot.application.console;

import com.lumensteward.clawbot.interfaces.dto.console.ConsoleEventVO;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 控制台事件环形缓冲与广播（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * <p>职责：
 * <ul>
 *   <li>维护容量 {@value #CAPACITY} 的环形队列，超出丢弃最旧事件（BR-11 观测窗口）；</li>
 *   <li>{@link #push(ConsoleEvent)} 分配全局单调序号并广播给全部活跃 SSE 连接；</li>
 *   <li>{@link #replay(long)} 返回序号大于 {@code lastEventId} 的事件，支撑断线续传；</li>
 *   <li>每个连接注册心跳任务（保活），{@link #unregister(SseEmitter)} 时取消。</li>
 * </ul>
 *
 * <p>线程安全：序号用 {@link AtomicLong}；连接集合用并发 Map；环形队列为线程安全双端队列。
 */
@Component
public class ConsoleEventBuffer {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEventBuffer.class);

    /** 环形缓冲容量（BR-11 观测通道，仅保留近窗口）。 */
    public static final int CAPACITY = 100;

    /** 心跳间隔（ms）：保活 SSE 连接，避免中间代理因空闲断开（C-4）。 */
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    private final AtomicLong seqCounter = new AtomicLong(0L);
    private final Deque<ConsoleEvent> deque = new LinkedBlockingDeque<>(CAPACITY);
    private final ConcurrentHashMap<SseEmitter, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "clawbot-console-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 广播事件：分配全局单调序号，写入环形缓冲，推送给所有活跃连接。
     * 任一连接发送失败即注销该连接（脱敏，不记录事件内容）。
     *
     * @param event 原始事件（序号由本方法回填）
     */
    public void push(ConsoleEvent event) {
        long seq = seqCounter.incrementAndGet();
        ConsoleEvent stamped = event.withSeq(seq);
        if (!deque.offerLast(stamped)) {
            // 容量已满：丢弃最旧（环形），重试入队
            deque.pollFirst();
            deque.offerLast(stamped);
        }
        for (SseEmitter emitter : heartbeats.keySet()) {
            try {
                emitter.send(toSseEvent(stamped));
            } catch (Exception e) {
                log.debug("SSE 推送失败，注销连接 seq={}", seq);
                unregister(emitter);
            }
        }
    }

    /**
     * 重放序号大于 lastEventId 的缓冲事件（续传）。
     *
     * @param lastEventId 客户端已收到的最大序号；≤0 表示从头
     * @return 有序事件列表
     */
    public List<ConsoleEvent> replay(long lastEventId) {
        List<ConsoleEvent> result = new ArrayList<>();
        for (ConsoleEvent event : deque) {
            if (event.getSeq() > lastEventId) {
                result.add(event);
            }
        }
        return result;
    }

    /**
     * 注册一个 SSE 连接：登记心跳任务（保活）。
     *
     * @param emitter 连接
     */
    public void register(SseEmitter emitter) {
        ScheduledFuture<?> future = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                unregister(emitter);
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        heartbeats.put(emitter, future);
    }

    /**
     * 注销连接并取消心跳。
     *
     * @param emitter 连接
     */
    public void unregister(SseEmitter emitter) {
        ScheduledFuture<?> future = heartbeats.remove(emitter);
        if (future != null) {
            future.cancel(true);
        }
    }

    /** 当前缓冲事件数（观测用）。 */
    public int bufferedSize() {
        return deque.size();
    }

    /** 当前活跃连接数（观测用）。 */
    public int activeConnections() {
        return heartbeats.size();
    }

    /**
     * 构造 SSE 事件：id=序号、name=事件类型、data=序列化视图（与前端解析一致）。
     *
     * @param event 控制台事件
     * @return SSE 事件构建器
     */
    public static SseEmitter.SseEventBuilder toSseEvent(ConsoleEvent event) {
        return SseEmitter.event()
                .id(String.valueOf(event.getSeq()))
                .name(event.getType().name())
                .data(ConsoleEventVO.from(event));
    }

    /** 容器销毁时关闭心跳调度器（守护线程，亦不阻塞 JVM 退出）。 */
    @PreDestroy
    public void destroy() {
        heartbeatScheduler.shutdownNow();
    }
}
