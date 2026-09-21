package com.lumensteward.clawbot.admin;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.application.admin.MemoryAdminService;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态库后台服务单测（W6-b）。
 *
 * <p>覆盖：过滤条件拼装、逻辑删除动作、审计留痕（reg_type=MEMORY、openid 脱敏、
 * 快照不含 openid），以及条目不存在时的 RESOURCE_NOT_FOUND。
 */
class MemoryAdminServiceTest {

    private final MemoryItemMapper mapper = mock(MemoryItemMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    private final MemoryAdminService service = new MemoryAdminService(mapper, auditLogService);

    /** 未启动 MyBatis 上下文，手工注册 TableInfo 供 Lambda 条件解析。 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MemoryItemEntity.class);
    }

    @Test
    @DisplayName("分页查询：三个过滤条件均可选，命中行数与分页元信息透传")
    void shouldPageWithOptionalFilters() {
        Page<MemoryItemEntity> mpPage = new Page<>(1, 20);
        mpPage.setRecords(List.of(entity(7L, "PERSON", "奶奶")));
        mpPage.setTotal(1L);
        when(mapper.selectPage(any(), any())).thenReturn(mpPage);

        PageResult<MemoryItemEntity> result =
                service.page(new PageQuery(1, 20), "openid-a", "PERSON", "ACTIVE");

        ArgumentCaptor<LambdaQueryWrapper<MemoryItemEntity>> wrapper = wrapperCaptor();
        verify(mapper).selectPage(any(), wrapper.capture());
        assertThat(result.getTotal()).isEqualTo(1L);
        assertThat(result.getList()).hasSize(1);
        // WHERE 段应包含三个等值条件与倒序
        String sql = wrapper.getValue().getTargetSql();
        assertThat(sql).contains("openid =").contains("kind =").contains("status =")
                .contains("ORDER BY").contains("last_seen_at DESC");
    }

    @Test
    @DisplayName("分页查询：过滤参数为空串/null 时不进 WHERE（全量口径）")
    void shouldSkipBlankFilters() {
        when(mapper.selectPage(any(), any())).thenReturn(new Page<>(1, 20));

        service.page(new PageQuery(1, 20), "  ", null, "");

        ArgumentCaptor<LambdaQueryWrapper<MemoryItemEntity>> wrapper = wrapperCaptor();
        verify(mapper).selectPage(any(), wrapper.capture());
        assertThat(wrapper.getValue().getTargetSql()).doesNotContain("openid =")
                .doesNotContain("kind =").doesNotContain("status =");
    }

    @Test
    @DisplayName("纠错删除：逻辑删除 + 审计（reg_type=MEMORY，target 脱敏，快照不含原始 openid）")
    void shouldSoftDeleteWithMaskedAudit() {
        MemoryItemEntity entity = entity(9L, "PREFERENCE", "不吃香菜");
        when(mapper.selectById(9L)).thenReturn(entity);
        when(mapper.deleteById(9L)).thenReturn(1);

        service.delete(9L, 1L, "10.0.0.1");

        verify(mapper).deleteById(9L);
        verify(auditLogService).record(eq(1L), eq("MEMORY"), eq("DELETE"),
                eq("wx-o****d-a1"), contains("\"PREFERENCE\""), isNull(), isNull(),
                eq("10.0.0.1"), eq(1));
    }

    @Test
    @DisplayName("纠错删除：条目不存在抛 RESOURCE_NOT_FOUND，不落审计")
    void shouldRejectDeleteWhenMissing() {
        when(mapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(404L, 1L, "10.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESOURCE_NOT_FOUND);

        verify(mapper, never()).deleteById(any(Long.class));
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(),
                any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<LambdaQueryWrapper<MemoryItemEntity>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    private static MemoryItemEntity entity(Long id, String kind, String name) {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setId(id);
        entity.setOpenid("wx-openid-a1");
        entity.setKind(kind);
        entity.setName(name);
        entity.setContent("内容-" + name);
        entity.setOrigin("AUTO_EXTRACT");
        entity.setStatus("ACTIVE");
        entity.setHitCount(1);
        return entity;
    }
}
