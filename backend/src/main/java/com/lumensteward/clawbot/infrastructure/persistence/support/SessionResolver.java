package com.lumensteward.clawbot.infrastructure.persistence.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 会话解析器（BR-07：上下文按 {@code openid} 隔离，键为 {@code conv:{openid}}）。
 *
 * <p>统一「按 openid 解析或创建 {@code wx_session}」的唯一实现，供消息落库（{@code WechatMessageService}）
 * 与对话引擎入口（{@code TextMessageHandler}）复用，避免逻辑分叉。
 * {@code wx_message.session_id} 与 {@code log_tool_call.session_id} 均为 NOT NULL 逻辑外键，故必须解析出有效 id。
 *
 * <p>失败降级：DB 不可用时返回 {@code null}，调用方据此走只读降级（不阻断主链路）。
 */
@Component
public class SessionResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionResolver.class);

    private final WxSessionMapper sessionMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param sessionMapper 会话 Mapper
     */
    public SessionResolver(WxSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    /**
     * 按 openid 解析会话，不存在则创建。
     *
     * @param openid 用户标识
     * @return 会话 id；不可用时返回 null
     */
    public Long resolveOrCreate(String openid) {
        return resolve(sessionMapper, openid);
    }

    /**
     * 解析（必要时创建）会话 id（静态实现，便于消息服务在保留既有构造签名下复用）。
     *
     * @param mapper 会话 Mapper（可为 null，表示无持久化能力）
     * @param openid 用户标识
     * @return 会话 id；不可用时返回 null
     */
    public static Long resolve(WxSessionMapper mapper, String openid) {
        if (mapper == null || openid == null || openid.isBlank()) {
            return null;
        }
        try {
            WxSessionEntity existing = mapper.selectOne(
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
            mapper.insert(created);
            return created.getId();
        } catch (RuntimeException e) {
            log.warn("解析会话失败（不阻断主链路）: openid={} err={}", MaskUtils.openid(openid), e.getMessage());
            return null;
        }
    }
}
