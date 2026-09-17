package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理员 Mapper（{@code sys_admin_user}）。
 *
 * <p>供 {@code AdminBootstrapRunner} 在启动期定位初始管理员并注入口令。
 */
@Mapper
public interface SysAdminUserMapper extends BaseMapper<SysAdminUserEntity> {
}
