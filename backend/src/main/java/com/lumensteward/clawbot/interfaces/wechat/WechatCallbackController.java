package com.lumensteward.clawbot.interfaces.wechat;

import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.fallback.FallbackReason;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.cache.DedupService;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitDecision;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitService;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParser;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifier;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 微信回调单一入口（架构 4.2 / SRS 8.1.1，FR-01 / FR-02）。
 *
 * <p><b>硬约束：</b>Mock 通道与真实回调<b>复用同一 Controller 与同一段验签代码</b>；GET（回显
 * {@code echostr}）与 POST（消息）走同一校验方法（BR-01 不可跳过）。
 *
 * <p>顺序：验签 → 幂等去重 → 限流 → 类型路由分发 → <b>先回执后推送</b>（BR-06）。任一 L1 异常由
 * {@code GlobalExceptionHandler} 映射为带 HTTP 语义的响应，<b>不</b>返回 500。
 *
 * <p><b>D2 修复（AC-A8 先回执后推送）：</b>分发链路在 {@link #RECEIPT_GRACE_MS} 回执窗口内完成后
 * 同步返回终态；若超过窗口（慢链路，如真实 LLM + 工具编排），则<b>先回执</b>——经客服消息即时
 * 推送占位文案（{@code 正在为你查询…}），并立即返回占位；随后<b>后推送</b>——链路在<b>有界线程池</b>
 * 中继续执行，终态回复经客服消息异步推送并落 {@code send_status}（{@code pushFinal}）。
 * 异步任务显式传播 {@code traceId}（MDC），异常经 {@code CompletableFuture} 捕获并落日志，不静默。
 */
@RestController
@RequestMapping("/api/wx/callback")
public class WechatCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WechatCallbackController.class);

    /**
     * 先回执窗口（ms）：链路在此窗口内完成则同步返回终态；超时则回占位并经客服消息异步推送终态。
     *
     * <p>取 700ms 以给「解析 + 验签 + 窗口」留出充足余量，稳态下回调总耗时稳定 ≤1s（AC-A8）。
     */
    static final long RECEIPT_GRACE_MS = 700L;

    private final WechatSignatureVerifier signatureVerifier;
    private final WechatMessageParser messageParser;
    private final DedupService dedupService;
    private final RateLimitService rateLimitService;
    private final MessageDispatcher messageDispatcher;
    private final WechatMessageService wechatMessageService;
    private final FallbackService fallbackService;

    /** 有界分发线程池（daemon），用于「后推送」阶段的链路执行（AC-A8）。 */
    private final ExecutorService pipelineExecutor;

    /**
     * 构造器注入（G-14）。
     *
     * @param signatureVerifier  签名校验器（Mock/Real 共用）
     * @param messageParser      报文解析器
     * @param dedupService       幂等去重
     * @param rateLimitService   限流
     * @param messageDispatcher  类型路由分发
     * @param wechatMessageService 消息服务（落库 + 回执 + 客服消息推送）
     * @param fallbackService    兜底文案
     */
    public WechatCallbackController(WechatSignatureVerifier signatureVerifier,
                                    WechatMessageParser messageParser,
                                    DedupService dedupService,
                                    RateLimitService rateLimitService,
                                    MessageDispatcher messageDispatcher,
                                    WechatMessageService wechatMessageService,
                                    FallbackService fallbackService) {
        this.signatureVerifier = signatureVerifier;
        this.messageParser = messageParser;
        this.dedupService = dedupService;
        this.rateLimitService = rateLimitService;
        this.messageDispatcher = messageDispatcher;
        this.wechatMessageService = wechatMessageService;
        this.fallbackService = fallbackService;
        this.pipelineExecutor = newPipelineExecutor();
    }

    /**
     * 构造有界分发线程池：核心 2、最大 4、队列 256、daemon 线程，空闲核心线程可回收。
     *
     * @return 分发线程池
     */
    private static ExecutorService newPipelineExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                runnable -> {
                    Thread thread = new Thread(runnable, "wx-callback-pipeline");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * 服务器配置校验（GET）：验签通过后原样回显 {@code echostr}（AC-A4）。
     *
     * @param signature 平台签名
     * @param timestamp 时间戳
     * @param nonce     随机串
     * @param echostr   回显串
     * @return echostr 原文
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public String verify(@RequestParam("signature") String signature,
                         @RequestParam("timestamp") String timestamp,
                         @RequestParam("nonce") String nonce,
                         @RequestParam("echostr") String echostr) {
        // 与 POST 复用同一段验签代码（BR-01）
        signatureVerifier.verify(signature, timestamp, nonce, null);
        log.debug("微信回调 GET 验签通过，回显 echostr");
        return echostr;
    }

    /**
     * 消息接收（POST）：验签 → 去重 → 限流 → 分发 → 先回执后推送。
     *
     * @param signature   平台签名
     * @param timestamp   时间戳
     * @param nonce       随机串
     * @param encryptType 加密类型（安全模式标记，可空）
     * @param body        原始报文
     * @param request     请求（取 IP）
     * @return 被动回执报文（终态或占位），或 {@code success}
     */
    @PostMapping(consumes = {MediaType.TEXT_XML_VALUE, MediaType.APPLICATION_XML_VALUE,
            MediaType.TEXT_PLAIN_VALUE, MediaType.ALL_VALUE})
    public String receive(@RequestParam("signature") String signature,
                          @RequestParam("timestamp") String timestamp,
                          @RequestParam("nonce") String nonce,
                          @RequestParam(value = "encrypt_type", required = false) String encryptType,
                          @RequestBody(required = false) String body,
                          HttpServletRequest request) {
        signatureVerifier.verify(signature, timestamp, nonce, null);

        InternalMessage message = messageParser.parse(body, request.getParameter("msg_type"));
        log.info("微信回调进入处理 msgType={} openid={}",
                message.msgType(), MaskUtils.openid(message.openid()));

        // L1 幂等去重：同 MsgId 10 次仅处理 1 次（AC-A3）
        if (!dedupService.markIfAbsent(message.msgId())) {
            log.info("重复消息幂等丢弃 msgId={}", message.msgId());
            return fallbackService.render(FallbackReason.MSG_DUPLICATED, Map.of());
        }

        // 限流：保护而非惩罚（BR-29），超限返回如实提示（不暴露内部）
        RateLimitDecision decision = rateLimitService.tryAcquire(message.openid(), clientIp(request));
        if (decision != RateLimitDecision.ALLOWED) {
            log.warn("触发限流 decision={} openid={}", decision, MaskUtils.openid(message.openid()));
            return wechatMessageService.handleInboundWithReceipt(message,
                    fallbackService.render(FallbackReason.RATE_LIMITED, Map.of()));
        }

        // 类型路由（BR-05 单一入口 + 策略化）+ 先回执后推送（BR-06 / AC-A8）
        return dispatchWithReceipt(message);
    }

    /**
     * 路由分发并落实「先回执后推送」（BR-06 / AC-A8）。
     *
     * <p>链路在 {@link #RECEIPT_GRACE_MS} 窗口内完成时，同步返回终态回复（多数快路径，如
     * image/voice 占位、事件处理）；超过窗口时必须先回执、后推送：占位文案经客服消息即时推送，
     * 终态由异步链路产出后经客服消息推送并落库。任一路径均先落库入站消息（AC-A1）。
     *
     * @param message 入站消息
     * @return 被动回执报文（终态 / 占位），或 {@code success}
     */
    private String dispatchWithReceipt(InternalMessage message) {
        String traceId = TraceContext.getTraceId();
        CompletableFuture<String> future = new CompletableFuture<>();

        if (!submitPipeline(traceId, message, future)) {
            // 线程池饱和等极端情形：退化为同步分发（保正确性，牺牲「先回执」时延）
            String reply = messageDispatcher.dispatch(message);
            return wechatMessageService.handleInboundWithReceipt(message, reply);
        }

        try {
            String reply = future.get(RECEIPT_GRACE_MS, TimeUnit.MILLISECONDS);
            // 快路径：链路在窗口内完成 → 同步返回终态回复
            return wechatMessageService.handleInboundWithReceipt(message, reply);
        } catch (TimeoutException timeout) {
            // 慢路径：「先回执」——占位经客服消息 ≤1s 推送；「后推送」——终态异步客服消息
            log.info("链路超过 {}ms 回执窗口，转先回执后推送 openid={}",
                    RECEIPT_GRACE_MS, MaskUtils.openid(message == null ? null : message.openid()));
            wechatMessageService.pushAsync(message.openid(), WechatMessageService.PLACEHOLDER_REPLY);
            future.thenAccept(reply -> pushFinalSafely(message, reply));
            // 落库入站消息 + 首交互用户 upsert（AC-A1 / AC-E5）；被动回复占位文案
            wechatMessageService.handleInboundWithReceipt(message, null);
            return WechatMessageService.PLACEHOLDER_REPLY;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("回执等待被中断 openid={}", MaskUtils.openid(message == null ? null : message.openid()));
            return wechatMessageService.handleInboundWithReceipt(message,
                    fallbackService.render(FallbackReason.EMPTY_RESULT, Map.of()));
        } catch (ExecutionException execution) {
            // 链路抛异常：如实兜底（不 500），异常原因落日志（不静默）
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            log.error("消息分发失败 openid={} err={}",
                    MaskUtils.openid(message == null ? null : message.openid()), cause.getMessage(), cause);
            return wechatMessageService.handleInboundWithReceipt(message,
                    fallbackService.render(FallbackReason.EMPTY_RESULT, Map.of()));
        }
    }

    /**
     * 将一次链路分发提交到有界线程池，并把 {@code traceId} 传播进异步任务（MDC）。
     *
     * @param traceId 当前链路标识（可能为空）
     * @param message 入站消息
     * @param future  用于承接结果的 CompletableFuture
     * @return 是否成功提交（池饱和返回 {@code false}）
     */
    private boolean submitPipeline(String traceId, InternalMessage message, CompletableFuture<String> future) {
        try {
            pipelineExecutor.execute(() -> {
                TraceContext.setTraceId(traceId);
                try {
                    future.complete(messageDispatcher.dispatch(message));
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                } finally {
                    TraceContext.clear();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            log.warn("分发线程池饱和，退化为同步处理: openid={}",
                    MaskUtils.openid(message == null ? null : message.openid()));
            return false;
        }
    }

    /**
     * 异步推送终态回复并落库（{@code send_status}）；异常落日志，绝不外泄、绝不静默。
     *
     * @param message   入站消息（关联 openid/sessionId）
     * @param replyText 终态文本（可能为空 → 忽略）
     */
    private void pushFinalSafely(InternalMessage message, String replyText) {
        try {
            wechatMessageService.pushFinal(message, replyText);
        } catch (RuntimeException e) {
            log.error("异步终态推送异常 openid={} err={}",
                    MaskUtils.openid(message == null ? null : message.openid()), e.getMessage(), e);
        }
    }

    private static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
