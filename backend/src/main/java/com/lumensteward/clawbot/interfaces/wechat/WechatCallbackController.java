package com.lumensteward.clawbot.interfaces.wechat;

import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.fallback.FallbackReason;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.cache.DedupService;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitService;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParser;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifier;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
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

/**
 * 微信回调单一入口（架构 4.2 / SRS 8.1.1，FR-01 / FR-02）。
 *
 * <p><b>硬约束：</b>Mock 通道与真实回调<b>复用同一 Controller 与同一段验签代码</b>；GET（回显
 * {@code echostr}）与 POST（消息）走同一校验方法（BR-01 不可跳过）。
 *
 * <p>顺序：验签 → 幂等去重 → 限流 → 类型路由分发 → 先回执（BR-06）。任一 L1 异常由
 * {@code GlobalExceptionHandler} 映射为带 HTTP 语义的响应，<b>不</b>返回 500。
 */
@RestController
@RequestMapping("/api/wx/callback")
public class WechatCallbackController {

    private static final Logger log = LoggerFactory.getLogger(WechatCallbackController.class);

    private final WechatSignatureVerifier signatureVerifier;
    private final WechatMessageParser messageParser;
    private final DedupService dedupService;
    private final RateLimitService rateLimitService;
    private final MessageDispatcher messageDispatcher;
    private final WechatMessageService wechatMessageService;
    private final FallbackService fallbackService;

    /**
     * 构造器注入（G-14）。
     *
     * @param signatureVerifier  签名校验器（Mock/Real 共用）
     * @param messageParser      报文解析器
     * @param dedupService       幂等去重
     * @param rateLimitService   限流
     * @param messageDispatcher  类型路由分发
     * @param wechatMessageService 消息服务（落库 + 回执）
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
     * 消息接收（POST）：验签 → 去重 → 限流 → 分发 → 回执。
     *
     * @param signature   平台签名
     * @param timestamp   时间戳
     * @param nonce       随机串
     * @param encryptType 加密类型（安全模式标记，可空）
     * @param body        原始报文
     * @param request     请求（取 IP）
     * @return 被动回复报文，或 {@code success}
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
        if (!rateLimitService.tryAcquire(message.openid(), clientIp(request))) {
            log.warn("触发限流 openid={}", MaskUtils.openid(message.openid()));
            return wechatMessageService.handleInboundWithReceipt(message,
                    fallbackService.render(FallbackReason.RATE_LIMITED, Map.of()));
        }

        // 类型路由（BR-05 单一入口 + 策略化）
        String replyText = messageDispatcher.dispatch(message);

        // 先回执后推送（BR-06）
        return wechatMessageService.handleInboundWithReceipt(message, replyText);
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
