package com.lumensteward.clawbot.application.wechat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumensteward.clawbot.common.enums.MessageRole;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatReplyBuilder;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatTransport;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.CustomerMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.SendResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import com.lumensteward.clawbot.infrastructure.persistence.repository.WxMessageRepository;
import com.lumensteward.clawbot.infrastructure.persistence.support.SessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 微信消息服务（架构 5.2 / SRS FR-03 / BR-06）。
 *
 * <p>职责：入站/出站消息落库（best-effort）、<b>首交互用户 upsert</b>（AC-E5/FR-16：保证后台用户列表与
 * 活跃度指标有数据）、被动回执构造、客服消息异步推送。围绕「先回执后推送」（BR-06）组织：
 * 占位回执即时返回（被动回执或客服消息），长链路终态经客服消息异步推送并落 {@code send_status}。
 *
 * <p>构造签名保持既有 4 参（兼容独立构造）；新增的 {@link WxUserMapper} 经 5 参重载由 Spring 注入。
 */
@Service
public class WechatMessageService {

    private static final Logger log = LoggerFactory.getLogger(WechatMessageService.class);

    /** 被动回执的固定 ack（微信侧不再重试）。 */
    private static final String ACK = "success";

    /** 占位回执文案（AC-A8："正在为你查询…"）。 */
    public static final String PLACEHOLDER_REPLY = "正在为你查询…";

    private final WechatTransport transport;
    private final WxMessageRepository messageRepository;
    private final WechatReplyBuilder replyBuilder;
    private final WxSessionMapper sessionMapper;
    private final WxUserMapper wxUserMapper;

    /**
     * Spring 装配用构造器（G-14）。
     *
     * @param transport         微信出站通道
     * @param messageRepository 消息仓库
     * @param replyBuilder      回复构造器
     * @param sessionMapper     会话 Mapper（落库时解析 sessionId）
     * @param wxUserMapper      用户 Mapper（首交互 upsert，可空则跳过）
     */
    @Autowired
    public WechatMessageService(WechatTransport transport, WxMessageRepository messageRepository,
                                WechatReplyBuilder replyBuilder, WxSessionMapper sessionMapper,
                                WxUserMapper wxUserMapper) {
        this.transport = transport;
        this.messageRepository = messageRepository;
        this.replyBuilder = replyBuilder;
        this.sessionMapper = sessionMapper;
        this.wxUserMapper = wxUserMapper;
    }

    /**
     * 兼容独立构造（无用户 upsert）。
     *
     * @param transport         微信出站通道
     * @param messageRepository 消息仓库
     * @param replyBuilder      回复构造器
     * @param sessionMapper     会话 Mapper
     */
    public WechatMessageService(WechatTransport transport, WxMessageRepository messageRepository,
                                WechatReplyBuilder replyBuilder, WxSessionMapper sessionMapper) {
        this(transport, messageRepository, replyBuilder, sessionMapper, null);
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
        recordOutbound(msg, finalReplyText, 1);
        return replyBuilder.buildTextReply(msg, finalReplyText);
    }

    /** 占位回执文案（AC-A8）。 */
    public String placeholderReply() {
        return PLACEHOLDER_REPLY;
    }

    /**
     * 异步推送客服消息（后推送，BR-06）——<b>仅发送</b>，不落库（用于占位回执）。
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
            log.warn("客服消息推送失败 openid={} err={}", MaskUtils.openid(openid), e.getMessage());
        }
    }

    /**
     * 推送终态结果（客服消息）：先落出站记录（含 {@code send_status}，AC-A8），再异步发送。
     *
     * @param inbound 入站消息（用于关联 openid/sessionId）
     * @param text    终态文本
     */
    public void pushFinal(InternalMessage inbound, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String openid = inbound == null ? null : inbound.openid();
        if (openid == null || openid.isBlank()) {
            return;
        }
        int status = 1;
        try {
            SendResult result = transport.sendCustomerMessage(openid, CustomerMessage.text(text));
            status = (result != null && result.success()) ? 1 : 2;
        } catch (RuntimeException e) {
            status = 2;
            log.warn("终态客服消息推送失败 openid={} err={}", MaskUtils.openid(openid), e.getMessage());
        }
        recordOutbound(inbound, text, status);
    }

    /**
     * 记录入站消息，并对首交互用户 upsert（AC-E5）——两处均为 best-effort。
     *
     * @param msg 入站消息
     */
    private void recordInbound(InternalMessage msg) {
        if (msg == null) {
            return;
        }
        try {
            WxMessageEntity entity = new WxMessageEntity();
            entity.setSessionId(SessionResolver.resolve(sessionMapper, msg.openid()));
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
        upsertUser(msg.openid());
    }

    private void recordOutbound(InternalMessage inbound, String text, int sendStatus) {
        try {
            WxMessageEntity entity = new WxMessageEntity();
            entity.setSessionId(SessionResolver.resolve(sessionMapper, inbound == null ? null : inbound.openid()));
            entity.setOpenid(inbound == null ? null : inbound.openid());
            entity.setMsgId(inbound == null ? null : inbound.msgId());
            entity.setRole(MessageRole.ASSISTANT.getWire());
            entity.setMsgType("text");
            entity.setContent(text);
            entity.setSendStatus(sendStatus);
            messageRepository.save(entity);
        } catch (RuntimeException e) {
            log.warn("记录出站消息失败（只读降级）: err={}", e.getMessage());
        }
    }

    /**
     * 首交互用户 upsert（AC-E5 / FR-16）：不存在则插入，存在则刷新 {@code last_interact_at}。
     *
     * @param openid 用户标识
     */
    private void upsertUser(String openid) {
        if (wxUserMapper == null || openid == null || openid.isBlank()) {
            return;
        }
        try {
            WxUserEntity existing = wxUserMapper.selectOne(
                    Wrappers.<WxUserEntity>lambdaQuery().eq(WxUserEntity::getOpenid, openid).last("limit 1"));
            if (existing == null) {
                WxUserEntity created = new WxUserEntity();
                created.setOpenid(openid);
                created.setStatus(1);
                created.setLastInteractAt(LocalDateTime.now());
                wxUserMapper.insert(created);
            } else {
                WxUserEntity touch = new WxUserEntity();
                touch.setId(existing.getId());
                touch.setLastInteractAt(LocalDateTime.now());
                wxUserMapper.updateById(touch);
            }
        } catch (RuntimeException e) {
            log.warn("用户 upsert 失败（只读降级）: openid={} err={}", MaskUtils.openid(openid), e.getMessage());
        }
    }
}
