package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户状态闸门（FR-16 / 迭代 2 T11）：被禁用用户<b>不触发 LLM</b>。
 *
 * <p>判定发生在<b>编排之前</b>（消息分发入口），因此禁用生效后，该用户的任何消息都不会进入
 * Agent Loop——既不消耗 token，也不会产生工具调用（FR-16 AC②："禁用后该用户不触发 LLM"，
 * 以日志与 {@code log_tool_call} 无新增记录为证）。
 *
 * <p><b>故障取向（诚实降级）：</b>{@code wx_user} 查询失败时<b>放行</b>并以 WARN 记录——
 * 此时无法确知用户状态，拦截正常用户与放行禁用用户之间，本项目选择"可用性优先 + 告警可见"，
 * 与数据库不可用时其他只读降级路径（SRS 9.5）保持一致的取向，而非静默拒绝。
 *
 * <p>查询走 {@code uk_openid} 唯一索引，无需额外缓存，保证"禁用即时生效"（改库即生效）。
 */
@Service
public class UserStatusGate {

    private static final Logger log = LoggerFactory.getLogger(UserStatusGate.class);

    /** 禁用状态值（{@code wx_user.status}：1-正常 0-禁用）。 */
    public static final int STATUS_DISABLED = 0;

    private final WxUserMapper wxUserMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param wxUserMapper 用户 Mapper
     */
    public UserStatusGate(WxUserMapper wxUserMapper) {
        this.wxUserMapper = wxUserMapper;
    }

    /**
     * 是否被禁用。
     *
     * @param openid 用户标识（可空）
     * @return true 表示该用户已被禁用，应拦截
     */
    public boolean isBlocked(String openid) {
        if (openid == null || openid.isBlank()) {
            return false;
        }
        try {
            WxUserEntity user = wxUserMapper.selectOne(new LambdaQueryWrapper<WxUserEntity>()
                    .eq(WxUserEntity::getOpenid, openid)
                    .last("LIMIT 1"));
            if (user == null) {
                // 新用户（首交互尚未 upsert）：不拦截，交由后续流程创建
                return false;
            }
            return user.getStatus() != null && user.getStatus() == STATUS_DISABLED;
        } catch (RuntimeException e) {
            log.warn("用户状态查询失败，按放行处理（只读降级）: openid={} err={}",
                    MaskUtils.openid(openid), e.getMessage());
            return false;
        }
    }
}
