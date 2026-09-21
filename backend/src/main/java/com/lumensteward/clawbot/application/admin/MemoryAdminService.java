package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 个人状态库后台服务（迭代 4 W6-b / §2.19 缺口闭合）。
 *
 * <p>两件事：①<b>只读查询</b>（BR-23 口径同监控，列表不含已被逻辑删除的行——
 * {@code deleted_at} 参与 MyBatis-Plus 逻辑删除，无需显式过滤）；
 * ②<b>人工纠错删除</b>——误抽取条目的唯一运营侧出口，逻辑删除（置 {@code deleted_at}），
 * 与唯一索引 {@code uk_openid_kind_name_live_marker} 的生成列语义一致：
 * 删除后该 (openid, kind, name) 槽位释放，生长管道后续可重新写入。删除写 {@code log_audit}
 * （reg_type=MEMORY），变更前快照仅留 kind/name/content，openid 已脱敏（BR-21）。
 *
 * <p>本服务<b>不提供编辑</b>：条目的修正语义由生长管道的「覆盖」承担（§2.19），
 * 后台只保留「查看 + 删除」，避免人工值与自动抽取值争抢同一事实。
 */
@Service
public class MemoryAdminService {

    private final MemoryItemMapper memoryItemMapper;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param memoryItemMapper 状态库 Mapper
     * @param auditLogService  审计日志服务
     */
    public MemoryAdminService(MemoryItemMapper memoryItemMapper, AuditLogService auditLogService) {
        this.memoryItemMapper = memoryItemMapper;
        this.auditLogService = auditLogService;
    }

    /**
     * 分页查询状态库条目（按最近出现时间倒序）。
     *
     * @param page   分页参数
     * @param openid 用户过滤（可空，原始 openid）
     * @param kind   条目类型过滤（可空）
     * @param status 状态过滤（可空：ACTIVE / SUPERSEDED）
     * @return 分页结果
     */
    public PageResult<MemoryItemEntity> page(PageQuery page, String openid, String kind, String status) {
        LambdaQueryWrapper<MemoryItemEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(notBlank(openid), MemoryItemEntity::getOpenid, openid)
                .eq(notBlank(kind), MemoryItemEntity::getKind, kind)
                .eq(notBlank(status), MemoryItemEntity::getStatus, status)
                .orderByDesc(MemoryItemEntity::getLastSeenAt);
        Page<MemoryItemEntity> mpPage = memoryItemMapper.selectPage(page.toPage(), wrapper);
        return PageResult.from(mpPage);
    }

    /**
     * 人工纠错删除一条条目（逻辑删除），并留审计。
     *
     * @param id      条目主键
     * @param adminId 操作人（可为 null）
     * @param ip      来源 IP
     * @throws BizException 条目不存在（含已被逻辑删除）时 {@link ErrorCode#RESOURCE_NOT_FOUND}
     */
    public void delete(Long id, Long adminId, String ip) {
        MemoryItemEntity entity = memoryItemMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "状态库条目不存在");
        }
        memoryItemMapper.deleteById(id);
        auditLogService.record(adminId, "MEMORY", "DELETE", MaskUtils.openid(entity.getOpenid()),
                snapshot(entity), null, null, ip, 1);
    }

    /**
     * 删除留痕的前值快照（脱敏口径：不含 openid，原始值经审计出参不再暴露）。
     *
     * @param entity 被删条目
     * @return JSON 快照
     */
    private static String snapshot(MemoryItemEntity entity) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("kind", entity.getKind());
        snapshot.put("name", entity.getName());
        snapshot.put("content", entity.getContent());
        snapshot.put("origin", entity.getOrigin());
        return JsonUtils.toJson(snapshot);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
