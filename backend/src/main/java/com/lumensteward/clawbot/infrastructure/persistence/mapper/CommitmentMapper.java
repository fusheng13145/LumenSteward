package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.CommitmentEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 承诺 Mapper（迭代 4 W7/W8）。
 *
 * <p>投递闩为条件 UPDATE（WHERE status='PENDING'），返回受影响行数——
 * 调用方按 {@code ==1} 判定「本方抢到」，保证至多一次投递。
 */
@Mapper
public interface CommitmentMapper extends BaseMapper<CommitmentEntity> {

    @Update("UPDATE biz_commitment SET status = 'DELIVERED', delivered_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markDelivered(@Param("id") Long id);
}
