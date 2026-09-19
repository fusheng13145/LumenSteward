package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.PetProfileMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户查询（架构 4.3 / SRS FR-16）。
 *
 * <p>读接口对全部角色开放；<b>写</b>（启用/禁用、档案维护）自迭代 2 T11 起统一收敛到
 * {@link UserAdminService}，本服务保持只读——与配置侧的读写分离约定一致，避免同一份
 * 变更逻辑在两处各自演化。
 */
@Service
public class UserQueryService {

    private final WxUserMapper wxUserMapper;
    private final WxSessionMapper wxSessionMapper;
    private final PetProfileMapper petProfileMapper;
    private final ToolCallLogMapper toolCallLogMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param wxUserMapper      用户 Mapper
     * @param wxSessionMapper   会话 Mapper
     * @param petProfileMapper  档案 Mapper
     * @param toolCallLogMapper 工具日志 Mapper
     */
    public UserQueryService(WxUserMapper wxUserMapper,
                            WxSessionMapper wxSessionMapper,
                            PetProfileMapper petProfileMapper,
                            ToolCallLogMapper toolCallLogMapper) {
        this.wxUserMapper = wxUserMapper;
        this.wxSessionMapper = wxSessionMapper;
        this.petProfileMapper = petProfileMapper;
        this.toolCallLogMapper = toolCallLogMapper;
    }

    /**
     * 分页查询用户。
     *
     * @param page      分页参数
     * @param keyword   关键字（openid/nickname 模糊，可空）
     * @param status    状态（可空）
     * @param startTime 最后交互时间下界（可空）
     * @param endTime   最后交互时间上界（可空）
     * @return 分页结果
     */
    public PageResult<WxUserEntity> page(PageQuery page, String keyword, Integer status,
                                         LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<WxUserEntity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(WxUserEntity::getOpenid, keyword)
                    .or().like(WxUserEntity::getNickname, keyword));
        }
        wrapper.eq(status != null, WxUserEntity::getStatus, status)
                .ge(startTime != null, WxUserEntity::getLastInteractAt, startTime)
                .le(endTime != null, WxUserEntity::getLastInteractAt, endTime)
                .orderByDesc(WxUserEntity::getLastInteractAt);
        Page<WxUserEntity> mpPage = wxUserMapper.selectPage(page.toPage(), wrapper);
        return PageResult.from(mpPage);
    }

    /**
     * 按主键查询用户。
     *
     * @param id 主键
     * @return 用户实体（不存在抛 {@code 30005}）
     */
    public WxUserEntity requireById(Long id) {
        WxUserEntity user = wxUserMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    /**
     * 统计用户的存活档案数。
     *
     * @param openid 用户
     * @return 数量
     */
    public long countPets(String openid) {
        Long count = petProfileMapper.selectCount(new LambdaQueryWrapper<PetProfileEntity>()
                .eq(PetProfileEntity::getOpenid, openid));
        return count == null ? 0L : count;
    }

    /**
     * 统计用户的会话数。
     *
     * @param openid 用户
     * @return 数量
     */
    public long countSessions(String openid) {
        Long count = wxSessionMapper.selectCount(new LambdaQueryWrapper<WxSessionEntity>()
                .eq(WxSessionEntity::getOpenid, openid));
        return count == null ? 0L : count;
    }

    /**
     * 统计用户的工具调用次数。
     *
     * @param openid 用户
     * @return 数量
     */
    public long countToolCalls(String openid) {
        Long count = toolCallLogMapper.selectCount(new LambdaQueryWrapper<ToolCallLogEntity>()
                .eq(ToolCallLogEntity::getOpenid, openid));
        return count == null ? 0L : count;
    }

    /**
     * 列出满足条件的用户（导出用，不分页；调用方须限制规模）。
     *
     * @param keyword   关键字（可空）
     * @param status    状态（可空）
     * @param startTime 下界（可空）
     * @param endTime   上界（可空）
     * @return 用户实体列表
     */
    public List<WxUserEntity> listForExport(String keyword, Integer status,
                                            LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<WxUserEntity> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(WxUserEntity::getOpenid, keyword)
                    .or().like(WxUserEntity::getNickname, keyword));
        }
        wrapper.eq(status != null, WxUserEntity::getStatus, status)
                .ge(startTime != null, WxUserEntity::getLastInteractAt, startTime)
                .le(endTime != null, WxUserEntity::getLastInteractAt, endTime)
                .orderByDesc(WxUserEntity::getLastInteractAt)
                .last("LIMIT 10000");
        return wxUserMapper.selectList(wrapper);
    }
}
