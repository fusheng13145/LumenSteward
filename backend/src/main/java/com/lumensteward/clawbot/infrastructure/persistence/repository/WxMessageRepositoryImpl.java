package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * {@link WxMessageRepository} 的 MyBatis-Plus 实现。
 *
 * <p>写失败降级：记录 WARN 后返回，不抛出（SRS 9.5 DB 不可用 → 只读降级）。
 */
@Repository
public class WxMessageRepositoryImpl implements WxMessageRepository {

    private static final Logger log = LoggerFactory.getLogger(WxMessageRepositoryImpl.class);

    private final WxMessageMapper wxMessageMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param wxMessageMapper 消息 Mapper
     */
    public WxMessageRepositoryImpl(WxMessageMapper wxMessageMapper) {
        this.wxMessageMapper = wxMessageMapper;
    }

    @Override
    public void save(WxMessageEntity entity) {
        try {
            wxMessageMapper.insert(entity);
        } catch (RuntimeException e) {
            log.warn("消息落库失败（只读降级）: err={}", e.getMessage());
        }
    }

    @Override
    public PageResult<WxMessageEntity> page(PageQuery query, String openid, String role) {
        PageQuery normalized = (query == null ? new PageQuery() : query).normalize();
        Page<WxMessageEntity> page = normalized.toPage();
        LambdaQueryWrapper<WxMessageEntity> wrapper = new LambdaQueryWrapper<>();
        if (openid != null && !openid.isBlank()) {
            wrapper.eq(WxMessageEntity::getOpenid, openid);
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(WxMessageEntity::getRole, role);
        }
        wrapper.orderByDesc(WxMessageEntity::getCreatedAt);
        return PageResult.from(wxMessageMapper.selectPage(page, wrapper));
    }
}
