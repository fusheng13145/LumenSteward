package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 微信用户 Mapper（{@code wx_user}）。
 *
 * <p>仅继承 MyBatis-Plus {@link BaseMapper} 以获得基础 CRUD 与分页能力；复杂查询在 T03/T05 按需补充。
 * {@code @Mapper} 使起始类所在包下的接口被自动扫描注册（无需额外 {@code @MapperScan}）。
 */
@Mapper
public interface WxUserMapper extends BaseMapper<WxUserEntity> {

    /**
     * 用户数据删除时匿名化账户锚点（FR-19 ②：不留可恢复的个人标识）。
     * openid 为主键级唯一标识，故以唯一匿名串替换；昵称/头像等 PII 清空，账户置为禁用。
     *
     * @param openid      原 openid
     * @param anonOpenid  唯一匿名标识
     * @return 更新行数
     */
    @Update("UPDATE wx_user SET openid = #{anonOpenid}, nickname = NULL, avatar_url = NULL, "
            + "unionid = NULL, status = 0 WHERE openid = #{openid}")
    int anonymize(@Param("openid") String openid, @Param("anonOpenid") String anonOpenid);
}
