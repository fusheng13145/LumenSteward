package com.lumensteward.clawbot.application.admin;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 用户写服务（FR-16 / 迭代 2 T11）：启停与档案维护，全部留痕。
 *
 * <p>与 {@link UserQueryService}（只读检索）分离，遵循与配置侧一致的读写分离约定。
 *
 * <p>验收对应：
 * <ul>
 *   <li>AC②禁用即时生效：状态写入 {@code wx_user.status}，入站消息经 {@link UserStatusGate}
 *       在编排前拦截，无需重启、无缓存延迟。</li>
 *   <li>AC③前后值留痕：启停与档案变更均写 {@code log_audit} 的 {@code before_value}/{@code after_value}，
 *       标识经 {@link MaskUtils#openid(String)} 脱敏（G-11 / BR-21）。</li>
 * </ul>
 */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    /** 昵称最大长度（与 DDL {@code wx_user.nickname} 一致）。 */
    private static final int NICKNAME_MAX_LENGTH = 64;

    private final WxUserMapper wxUserMapper;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param wxUserMapper    用户 Mapper
     * @param auditLogService 审计写服务
     */
    public UserAdminService(WxUserMapper wxUserMapper, AuditLogService auditLogService) {
        this.wxUserMapper = wxUserMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 启用/禁用用户（SUPER_ADMIN）。
     *
     * @param id      用户主键
     * @param status  目标状态：1-启用 0-禁用
     * @param adminId 操作人
     * @param ip      来源 IP
     */
    public void updateStatus(Long id, Integer status, Long adminId, String ip) {
        if (status == null || (status != 0 && status != 1)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "状态取值非法（0/1）");
        }
        WxUserEntity user = requireById(id);
        Integer before = user.getStatus();
        user.setStatus(status);
        wxUserMapper.updateById(user);
        audit(adminId, status == 1 ? "USER_ENABLE" : "USER_DISABLE",
                MaskUtils.openid(user.getOpenid()), String.valueOf(before), String.valueOf(status),
                null, ip);
        log.info("用户状态已变更 id={} status {}→{}（即时生效，被禁用用户不再触发 LLM）",
                id, before, status);
    }

    /**
     * 维护用户档案（昵称）。
     *
     * @param id       用户主键
     * @param nickname 新昵称（可空表示清空；超长即拒绝）
     * @param adminId  操作人
     * @param ip       来源 IP
     * @param reason   变更原因（可空）
     */
    public void updateProfile(Long id, String nickname, Long adminId, String ip, String reason) {
        String normalized = nickname == null ? "" : nickname.trim();
        if (normalized.length() > NICKNAME_MAX_LENGTH) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "昵称超长（上限 " + NICKNAME_MAX_LENGTH + "）");
        }
        WxUserEntity user = requireById(id);
        String before = user.getNickname();
        if (normalized.equals(before == null ? "" : before)) {
            // 无实质变更：不写库、不留痕，避免刷审计
            return;
        }
        user.setNickname(normalized.isEmpty() ? null : normalized);
        wxUserMapper.updateById(user);
        audit(adminId, "USER_PROFILE_UPDATE", MaskUtils.openid(user.getOpenid()),
                before, user.getNickname(), reason, ip);
    }

    /**
     * 按主键取用户（不存在即拒绝）。
     *
     * @param id 主键
     * @return 用户实体
     */
    public WxUserEntity requireById(Long id) {
        WxUserEntity user = wxUserMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "用户不存在");
        }
        return user;
    }

    private void audit(Long adminId, String action, String target, String before, String after,
                       String reason, String ip) {
        try {
            auditLogService.record(adminId, "USER", action, target, before, after, reason, ip, 1);
        } catch (RuntimeException e) {
            log.warn("用户变更审计写失败: err={}", e.getMessage());
        }
    }
}
