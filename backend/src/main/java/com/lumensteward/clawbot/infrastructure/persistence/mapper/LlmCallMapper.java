package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LlmCallEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * LLM 调用计量明细 Mapper（B-4 / W5）；读侧聚合走 {@code selectMaps}，不在本接口写业务。
 */
@Mapper
public interface LlmCallMapper extends BaseMapper<LlmCallEntity> {

    /**
     * 按时间物理清理（FR-19 ①：计量明细保留 180 天）。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    @Update("DELETE FROM log_llm_call WHERE created_at < #{cutoff}")
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 将指定（脱敏形态）openid 的计量行匿名化（FR-19 ②）。
     *
     * <p>参数须是 {@code MaskUtils.openid(...)} 的结果——本表从不落原始 openid，
     * 用原文匹配将永远命中 0 行。
     *
     * @param maskedOpenid 脱敏后的用户标识
     * @param anon         匿名标识
     * @return 更新行数
     */
    @Update("UPDATE log_llm_call SET openid = #{anon} WHERE openid = #{maskedOpenid}")
    int anonymizeOpenid(@Param("maskedOpenid") String maskedOpenid, @Param("anon") String anon);
}
