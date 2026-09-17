package com.lumensteward.clawbot.application.wechat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumensteward.clawbot.common.enums.MessageRole;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatReplyBuilder;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatTransport;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.CustomerMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.repository.WxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 微信消息服务（架构 5.2 / SRS FR-03 / BR-06）。
 *
 * <p>职责：入站/出站消息落库（best-effort）、被动回执构造、客服消息异步推送。围绕「先回执后推送」
 * （BR-06）组织：先同步返回被动回复（或 {@code success} 回执），长链路结果再以客服消息推送。
 */
@Service
public class WechatMessageService {

    private static final Logger log = LoggerFactory.getLogger(WechatMessageService.class);

    /** 被动回执的固定 ack（微信侧不再重试）。 */
    private static final String ACK = "success";

    private final WechatTransport transport;
    private final WxMessageRepository messageRepository;
    private final WechatReplyBuilder replyBuilder;
    private final WxSessionMapper sessionMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param transport         微信出站通道
     * @param messageRepository 消息仓库
     * @param replyBuilder      回复构造器
     * @param sessionMapper     会话 Mapper（用于落库时解析 sessionId）
     */
    public WechatMessageService(WechatTransport transport, WxMessageRepository messageRepository,
                                WechatReplyBuilder replyBuilder, WxSessionMapper sessionMapper) {
        this.transport = transport;
        this.messageRepository = messageRepository;
        this.replyBuilder = replyBuilder;
        this.sessionMapper = sessionMapper;
    }

    /**
     * 记录入站消息并生成回执报文（先回执）。
     *
     * @param msg            入站消息
     * @param finalReplyText 终态回复文本；为空表示无需被动回复（仅回 {@code success}）
     * @return 平台被动回复报文，或 {@code success}
     */
    public String handleInboundWithReceipt(InternalMessage msg, String finalReplyText) {
        recordInbound(msg);
        if (finalReplyText == null || finalReplyText.isBlank()) {
            return ACK;
        }
        recordOutbound(msg, finalReplyText);
        return replyBuilder.buildTextReply(msg, finalReplyText);
    }

    /**
     * 异步推送客服消息（后推送，BR-06）。
     *
     * @param openid 接收用户
     * @param text   文本
     */
    public void pushAsync(String openid, String text) {
        if (openid == null || text == null || text.isBlank()) {
            return;
        }
        try {
            transport.sendCustomerMessage(openid, CustomerMessage.text(text));
        } catch (RuntimeException e) {
            // 发送失败静默记录（SRS 2.3.5 L4：指数退避重试 ≤ 3 次；MVP 记录后放弃）
            log.warn("客服消息推送失败 openid={} err={}", MaskUtils.openid(openid), e.getMessage());
        }
    }

    private void recordInbound(InternalMessage msg) {
        if (msg == null) {
            return;
        }
        try {
            WxMessageEntity entity = new WxMessageEntity();
            entity.setSessionId(resolveSessionId(msg.openid()));
            entity.setOpenid(msg.openid());
            entity.setMsgId(msg.msgId());
            entity.setRole(MessageRole.USER.getWire());
            entity.setMsgType(msg.msgType());
            entity.setContent(msg.content());
            entity.setMediaId(msg.mediaId());
            entity.setSendStatus(1);
            messageRepository.save(entity);
        } catch (RuntimeException e) {
            log.warn("记录入站消息失败（只读降级）: err={}", e.getMessage());
        }
    }

    private void recordOutbound(InternalMessage inbound, String text) {
        try {
            WxMessageEntity entity = new WxMessageEntity();
            entity.setSessionId(resolveSessionId(inbound == null ? null : inbound.openid()));
            entity.setOpenid(inbound == null ? null : inbound.openid());
            entity.setMsgId(inbound == null ? null : inbound.msgId());
            entity.setRole(MessageRole.ASSISTANT.getWire());
            entity.setMsgType("text");
            entity.setContent(text);
            entity.setSendStatus(1);
            messageRepository.save(entity);
        } catch (RuntimeException e) {
            log.warn("记录出站消息失败（只读降级）: err={}", e.getMessage());
        }
    }

    /**
     * 解析（必要时创建）会话 id（BR-07：上下文按用户隔离，键为 conv:{openid}）。
     *
     * @param openid 用户标识
     * @return 会话 id；不可用时返回 null
     */
    private Long resolveSessionId(String openid) {
        if (openid == null || openid.isBlank()) {
            return null;
        }
        try {
            WxSessionEntity existing = sessionMapper.selectOne(
                    Wrappers.<WxSessionEntity>lambdaQuery().eq(WxSessionEntity::getOpenid, openid).last("limit 1"));
            if (existing != null) {
                return existing.getId();
            }
            WxSessionEntity created = new WxSessionEntity();
            created.setOpenid(openid);
            created.setContextKey("conv:" + openid);
            created.setState(SessionState.IDLE.getCode());
            created.setTurnCount(0);
            created.setLastActiveAt(LocalDateTime.now());
            sessionMapper.insert(created);
            return created.getId();
        } catch (RuntimeException e) {
            log.warn("解析会话失败（不阻断主链路）: err={}", e.getMessage());
            return null;
        }
    }
}
