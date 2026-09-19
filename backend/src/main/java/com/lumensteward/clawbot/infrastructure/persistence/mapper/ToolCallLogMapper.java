package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 工具调用日志 Mapper（{@code log_tool_call}）。
 */
@Mapper
public interface ToolCallLogMapper extends BaseMapper<ToolCallLogEntity> {

    /**
     * 按时间物理清理（FR-19 ①：工具日志保留 180 天）。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    @Update("DELETE FROM log_tool_call WHERE created_at < #{cutoff}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 将指定 openid 的日志匿名化（FR-19 用户删除请求：保留运维记录但不含 PII）。
     *
     * @param openid      原用户标识
     * @param anonOpenid  匿名标识
     * @return 更新行数
     */
    @Update("UPDATE log_tool_call SET openid = #{anonOpenid} WHERE openid = #{openid}")
    int anonymizeOpenid(@Param("openid") String openid, @Param("anonOpenid") String anonOpenid);
}
