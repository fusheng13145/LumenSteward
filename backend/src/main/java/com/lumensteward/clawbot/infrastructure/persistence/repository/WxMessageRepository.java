package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;

/**
 * 消息持久化仓库（架构 5.4）。
 *
 * <p>封装 MyBatis-Plus 访问，向上层暴露稳定契约。落库失败不抛出中断链路（SRS 9.5：DB 不可用时
 * 只读降级），由实现侧记录并降级。
 */
public interface WxMessageRepository {

    /**
     * 保存一条消息。
     *
     * @param entity 消息实体
     */
    void save(WxMessageEntity entity);

    /**
     * 分页查询消息。
     *
     * @param query  分页参数
     * @param openid 用户过滤（可空）
     * @param role   角色过滤（可空）
     * @return 分页结果
     */
    PageResult<WxMessageEntity> page(PageQuery query, String openid, String role);
}
