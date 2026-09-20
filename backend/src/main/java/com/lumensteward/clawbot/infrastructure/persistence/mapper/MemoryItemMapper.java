package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 个人状态库 Mapper（{@code biz_memory_item}）。
 *
 * <p>{@code live_marker} 为数据库生成列，{@link MemoryItemEntity} 未声明该字段；
 * 活记录唯一性由 {@code uk_openid_kind_name_live_marker} 兜底，应用层查重为首要防线（架构 3.3）。
 */
@Mapper
public interface MemoryItemMapper extends BaseMapper<MemoryItemEntity> {

    /**
     * 按用户物理删除全部条目（FR-19 ②：含 ACTIVE 与 SUPERSEDED 历史，忽略逻辑删除）。
     *
     * @param openid 用户标识
     * @return 删除行数
     */
    @Update("DELETE FROM biz_memory_item WHERE openid = #{openid}")
    int deleteAllByOpenid(@Param("openid") String openid);

    /**
     * 物理清理早于截止时间的<b>已覆盖</b>历史条目（生效事实不自动过期）。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    @Update("DELETE FROM biz_memory_item WHERE status = 'SUPERSEDED' AND updated_at < #{cutoff}")
    int deleteSupersededBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 强化一条既有条目：出现次数 +1、最近出现时间刷新。
     *
     * <p>用单条 UPDATE 而非「读—改—写」，避免并发抽取下的计数丢失。
     *
     * @param id 条目主键
     * @return 更新行数
     */
    @Update("UPDATE biz_memory_item SET hit_count = hit_count + 1, last_seen_at = NOW() WHERE id = #{id}")
    int reinforce(@Param("id") Long id);
}
