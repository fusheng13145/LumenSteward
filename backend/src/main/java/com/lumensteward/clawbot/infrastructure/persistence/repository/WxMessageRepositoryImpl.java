package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.observability.PersistenceWriteFailureReporter;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import org.springframework.stereotype.Repository;

/**
 * {@link WxMessageRepository} 的 MyBatis-Plus 实现。
 *
 * <p>写失败降级：不抛出、不阻断（SRS 9.5 DB 不可用 → 只读降级），但<b>不再静默</b>：经
 * {@link PersistenceWriteFailureReporter} 以 ERROR 级日志（含表名/列名/完整异常）并计入指标
 * {@code persistence.write.failures}（D7 修复）。
 */
@Repository
public class WxMessageRepositoryImpl implements WxMessageRepository {

    /** 本仓库写入的目标表名（用于失败上报）。 */
    private static final String TABLE = "wx_message";

    private final WxMessageMapper wxMessageMapper;
    private final PersistenceWriteFailureReporter writeFailureReporter;

    /**
     * 构造器注入（G-14）。
     *
     * @param wxMessageMapper       消息 Mapper
     * @param writeFailureReporter  写入失败上报器（日志 + 指标）
     */
    public WxMessageRepositoryImpl(WxMessageMapper wxMessageMapper,
                                   PersistenceWriteFailureReporter writeFailureReporter) {
        this.wxMessageMapper = wxMessageMapper;
        this.writeFailureReporter = writeFailureReporter;
    }

    @Override
    public void save(WxMessageEntity entity) {
        try {
            wxMessageMapper.insert(entity);
        } catch (RuntimeException e) {
            writeFailureReporter.report(TABLE, e);
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
