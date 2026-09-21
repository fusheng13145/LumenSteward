package com.lumensteward.clawbot.interfaces.assembler;

import com.lumensteward.clawbot.application.gray.GrayFeature;
import com.lumensteward.clawbot.application.gray.GrayReleaseService;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.interfaces.dto.audit.AuditLogVO;
import com.lumensteward.clawbot.interfaces.dto.config.ConfigVO;
import com.lumensteward.clawbot.interfaces.dto.gray.GrayPreviewVO;
import com.lumensteward.clawbot.interfaces.dto.memory.MemoryItemVO;
import com.lumensteward.clawbot.interfaces.dto.pet.PetVO;
import com.lumensteward.clawbot.interfaces.dto.session.MessageVO;
import com.lumensteward.clawbot.interfaces.dto.session.SessionVO;
import com.lumensteward.clawbot.interfaces.dto.toollog.ToolLogDetailVO;
import com.lumensteward.clawbot.interfaces.dto.toollog.ToolLogVO;
import com.lumensteward.clawbot.interfaces.dto.user.UserDetailVO;
import com.lumensteward.clawbot.interfaces.dto.user.UserVO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 出参统一脱敏装配器（G-11 / BR-21 / BR-15 / BR-30）。
 *
 * <p>所有管理后台出参的 {@code openid} 一律经 {@link MaskUtils#openid(String)} 处理
 * （前 4 + {@code ****} + 后 4），{@code SECRET} 类型配置值经 {@link MaskUtils#secret(String)}
 * （仅尾号）。<b>Controller 不得手工置空或截断敏感字段</b>，一律走本装配器。
 */
@Component
public class MaskingAssembler {

    /** 值类型：密钥（仅返回尾号，不泄露明文）。 */
    private static final String TYPE_SECRET = "SECRET";

    /**
     * 用户 → 视图。
     *
     * @param entity 用户实体
     * @return 视图
     */
    public UserVO toUserVO(WxUserEntity entity) {
        if (entity == null) {
            return null;
        }
        return new UserVO(entity.getId(), MaskUtils.openid(entity.getOpenid()), entity.getNickname(),
                entity.getStatus(), entity.getLastInteractAt(), entity.getCreatedAt());
    }

    /**
     * 用户详情 → 视图。
     *
     * @param entity        用户实体
     * @param petCount      存活档案数
     * @param sessionCount  会话数
     * @param toolCallCount 工具调用数
     * @return 视图
     */
    public UserDetailVO toUserDetailVO(WxUserEntity entity, long petCount, long sessionCount,
                                       long toolCallCount) {
        if (entity == null) {
            return null;
        }
        return new UserDetailVO(entity.getId(), MaskUtils.openid(entity.getOpenid()),
                entity.getNickname(), entity.getStatus(), entity.getLastInteractAt(),
                entity.getCreatedAt(), petCount, sessionCount, toolCallCount);
    }

    /**
     * 会话 → 视图。
     *
     * @param entity 会话实体
     * @return 视图
     */
    public SessionVO toSessionVO(WxSessionEntity entity) {
        if (entity == null) {
            return null;
        }
        return new SessionVO(entity.getId(), MaskUtils.openid(entity.getOpenid()),
                entity.getContextKey(), entity.getState(), entity.getTurnCount(),
                entity.getLastActiveAt(), entity.getCreatedAt());
    }

    /**
     * 消息 → 视图。
     *
     * @param entity 消息实体
     * @return 视图
     */
    public MessageVO toMessageVO(WxMessageEntity entity) {
        if (entity == null) {
            return null;
        }
        return new MessageVO(entity.getId(), entity.getSessionId(),
                MaskUtils.openid(entity.getOpenid()), entity.getMsgId(), entity.getRole(),
                entity.getMsgType(), entity.getContent(), entity.getToolName(),
                entity.getTokenCount(), entity.getSendStatus(), entity.getCreatedAt());
    }

    /**
     * 工具日志 → 列表视图。
     *
     * @param entity 日志实体
     * @return 视图
     */
    public ToolLogVO toToolLogVO(ToolCallLogEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ToolLogVO(entity.getId(), entity.getTraceId(),
                MaskUtils.openid(entity.getOpenid()), entity.getSessionId(), entity.getToolName(),
                entity.getCallSeq(), entity.getStatus(), entity.getErrorType(),
                entity.getFallbackReason(), entity.getLatencyMs(), entity.getLlmRound(),
                entity.getCreatedAt());
    }

    /**
     * 工具日志 → 详情视图（含入参/结果/耗时/降级原因）。
     *
     * @param entity 日志实体
     * @return 视图
     */
    public ToolLogDetailVO toToolLogDetailVO(ToolCallLogEntity entity) {
        if (entity == null) {
            return null;
        }
        return new ToolLogDetailVO(entity.getId(), entity.getTraceId(),
                MaskUtils.openid(entity.getOpenid()), entity.getSessionId(), entity.getToolName(),
                entity.getCallSeq(), entity.getParamsJson(), entity.getResultJson(),
                entity.getStatus(), entity.getErrorType(), entity.getFallbackReason(),
                entity.getLatencyMs(), entity.getLlmRound(), entity.getCreatedAt());
    }

    /**
     * 审计日志 → 视图。
     *
     * @param entity 审计实体
     * @return 视图
     */
    public AuditLogVO toAuditLogVO(AuditLogEntity entity) {
        if (entity == null) {
            return null;
        }
        return new AuditLogVO(entity.getId(), entity.getAdminId(), entity.getRegType(),
                entity.getAction(), entity.getTarget(), entity.getBeforeValue(),
                entity.getAfterValue(), entity.getReason(), entity.getIp(), entity.getResult(),
                entity.getCreatedAt());
    }

    /**
     * 宠物档案 → 视图。
     *
     * @param view 领域视图
     * @return 视图
     */
    public PetVO toPetVO(PetProfileView view) {
        if (view == null) {
            return null;
        }
        return new PetVO(view.id(), MaskUtils.openid(view.openid()), view.petName(), view.petType(),
                view.breed(), view.gender(), view.birthday(), view.weightKg(), view.personality(),
                view.notes(), view.createdAt(), view.updatedAt());
    }

    /**
     * 状态库条目 → 视图（W6-b）。
     *
     * @param entity 条目实体
     * @return 视图
     */
    public MemoryItemVO toMemoryItemVO(MemoryItemEntity entity) {
        if (entity == null) {
            return null;
        }
        return new MemoryItemVO(entity.getId(), MaskUtils.openid(entity.getOpenid()),
                entity.getKind(), entity.getName(), entity.getContent(), entity.getOrigin(),
                entity.getExtractor(), entity.getConfidence(), entity.getSourceSessionId(),
                entity.getSourceTraceId(), entity.getStatus(), entity.getSupersedesId(),
                entity.getHitCount(), entity.getFirstSeenAt(), entity.getLastSeenAt());
    }

    /**
     * 灰度命中预览 → 视图（FR-22 / W2）；原始 openid 只以脱敏形态回显（BR-21）。
     *
     * @param feature   灰度功能
     * @param rawOpenid 原始用户标识（仅用于脱敏展示，不参与判定）
     * @param decision  判定明细
     * @return 视图
     */
    public GrayPreviewVO toGrayPreviewVO(GrayFeature feature, String rawOpenid,
                                         GrayReleaseService.Decision decision) {
        return new GrayPreviewVO(feature.code(), feature.label(), MaskUtils.openid(rawOpenid),
                decision.percent(), decision.bucket(), decision.hit(), decision.reason());
    }

    /**
     * 系统配置 → 视图（SECRET 仅返回尾号）。
     *
     * @param entity 配置实体
     * @return 视图
     */
    public ConfigVO toConfigVO(SysConfigEntity entity) {
        if (entity == null) {
            return null;
        }
        boolean secret = TYPE_SECRET.equalsIgnoreCase(entity.getValueType());
        String value = secret ? MaskUtils.secret(entity.getConfigValue()) : entity.getConfigValue();
        boolean encrypted = entity.getIsEncrypted() != null && entity.getIsEncrypted() == 1;
        return new ConfigVO(entity.getConfigKey(), value, entity.getValueType(),
                entity.getCategory(), entity.getDescription(), encrypted, entity.getUpdatedAt());
    }

    /**
     * 构造运行模式展示项（Mock/Real 标识，供配置页展示；非落库项）。
     *
     * @param configKey   展示键名
     * @param value       展示值
     * @param description 说明
     * @return 配置视图
     */
    public ConfigVO runtimeConfig(String configKey, String value, String description) {
        return new ConfigVO(configKey, value, "STRING", "runtime", description, false, null);
    }

    /**
     * 列表映射（逐项装配）。
     *
     * @param source 源列表
     * @param mapper 映射函数
     * @param <E>    源类型
     * @param <R>    目标类型
     * @return 目标列表
     */
    public <E, R> List<R> mapList(List<E> source, Function<E, R> mapper) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        return source.stream().map(mapper).toList();
    }

    /**
     * 分页映射：保留分页元信息，仅转换记录。
     *
     * @param source 源分页结果
     * @param mapper 映射函数
     * @param <E>    源类型
     * @param <R>    目标类型
     * @return 目标分页结果
     */
    public <E, R> PageResult<R> assemblePage(PageResult<E> source, Function<E, R> mapper) {
        if (source == null) {
            return PageResult.empty(1, 20);
        }
        return PageResult.of(mapList(source.getList(), mapper), source.getTotal(),
                source.getPage(), source.getPageSize());
    }
}
