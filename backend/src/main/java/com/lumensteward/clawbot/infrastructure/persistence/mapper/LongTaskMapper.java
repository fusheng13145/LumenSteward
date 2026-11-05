package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LongTaskEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 长任务 Mapper（迭代 4 W15）。
 *
 * <p>状态迁移一律条件 UPDATE（WHERE 带前置状态 / 幂等闩），返回受影响行数——
 * 「是否本方生效」由调用方按 {@code ==1} 判定，见 {@code LongTaskStore} 契约。
 */
@Mapper
public interface LongTaskMapper extends BaseMapper<LongTaskEntity> {

    @Update("UPDATE biz_long_task SET status = 'RUNNING', attempts = attempts + 1, started_at = NOW() "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int claimPending(@Param("id") Long id);

    @Update("UPDATE biz_long_task SET status = 'SUCCEEDED', result_json = #{resultJson}, finished_at = NOW() "
            + "WHERE id = #{id} AND status = 'RUNNING'")
    int completeSuccess(@Param("id") Long id, @Param("resultJson") String resultJson);

    @Update("UPDATE biz_long_task SET status = 'FAILED', error_type = #{errorType}, finished_at = NOW() "
            + "WHERE id = #{id} AND status = 'RUNNING'")
    int completeFailure(@Param("id") Long id, @Param("errorType") String errorType);

    @Update("UPDATE biz_long_task SET delivered_at = NOW() WHERE id = #{id} AND delivered_at IS NULL")
    int markDelivered(@Param("id") Long id);

    @Update("UPDATE biz_long_task SET status = 'PENDING', started_at = NULL WHERE status = 'RUNNING'")
    int requeueRunning();
}
