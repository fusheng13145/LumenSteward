package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话与消息查询（架构 4.3 / GET /api/sessions、/api/sessions/{id}/messages）。
 *
 * <p>只读；角色约束由控制器 {@code @PreAuthorize} 施加（全部角色可读）。
 */
@Service
public class SessionQueryService {

    private final WxSessionMapper wxSessionMapper;
    private final WxMessageMapper wxMessageMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param wxSessionMapper 会话 Mapper
     * @param wxMessageMapper 消息 Mapper
     */
    public SessionQueryService(WxSessionMapper wxSessionMapper, WxMessageMapper wxMessageMapper) {
        this.wxSessionMapper = wxSessionMapper;
        this.wxMessageMapper = wxMessageMapper;
    }

    /**
     * 分页查询会话（按最后活跃时间倒序）。
     *
     * @param page   分页参数
     * @param openid 用户过滤（可空）
     * @param state  状态过滤（可空）
     * @return 分页结果
     */
    public PageResult<WxSessionEntity> page(PageQuery page, String openid, String state) {
        LambdaQueryWrapper<WxSessionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(notBlank(openid), WxSessionEntity::getOpenid, openid)
                .eq(notBlank(state), WxSessionEntity::getState, state)
                .orderByDesc(WxSessionEntity::getLastActiveAt);
        Page<WxSessionEntity> mpPage = wxSessionMapper.selectPage(page.toPage(), wrapper);
        return PageResult.from(mpPage);
    }

    /**
     * 查询会话消息流。
     *
     * @param sessionId 会话主键
     * @param role      角色过滤（可空）
     * @param msgType   消息类型过滤（可空）
     * @return 消息列表（时间正序）
     */
    public List<WxMessageEntity> messages(Long sessionId, String role, String msgType) {
        LambdaQueryWrapper<WxMessageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(WxMessageEntity::getSessionId, sessionId)
                .eq(notBlank(role), WxMessageEntity::getRole, role)
                .eq(notBlank(msgType), WxMessageEntity::getMsgType, msgType)
                .orderByAsc(WxMessageEntity::getCreatedAt)
                .last("LIMIT 1000");
        return wxMessageMapper.selectList(wrapper);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
