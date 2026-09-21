package com.lumensteward.clawbot.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.admin.ReplayService;
import com.lumensteward.clawbot.application.safety.ActionClaimExtractor;
import com.lumensteward.clawbot.application.safety.RuleBasedConsistencyChecker;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.config.properties.SafetyProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.support.ToolRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具调用回放单测（A-2 / T12）。
 *
 * <p>AC①：可基于历史 {@code log_tool_call} 回放；AC②：能复现"模型声称成功但工具失败"的拦截。
 */
class ReplayServiceTest {

    private static final String TRACE = "trace-replay-1";
    private static final String CLAIMING_REPLY = "我已查到您的快递位置。";

    private final ToolCallLogMapper mapper = mock(ToolCallLogMapper.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final RuleBasedConsistencyChecker checker = new RuleBasedConsistencyChecker(
            new ActionClaimExtractor(ToolRegistries.productionTools()),
            new SafetyProperties("classpath:no-such.txt", true, false));

    private final ReplayService service =
            new ReplayService(mapper, registry, checker, auditLogService);

    @Test
    @DisplayName("AC① 单条回放：用当前实现重跑历史入参，返回本次结果")
    void shouldReplaySingleCall() {
        Tool tool = mock(Tool.class);
        when(tool.name()).thenReturn("query_express");
        when(tool.readOnly()).thenReturn(true);
        when(tool.execute(any(ToolContext.class), any(JsonNode.class)))
                .thenReturn(ToolResult.success(JsonUtils.readTree("{\"status\":\"已签收\"}"), 3L));
        when(registry.find("query_express")).thenReturn(Optional.of(tool));
        when(mapper.selectById(11L)).thenReturn(log(11L, "query_express", "{\"tracking_no\":\"SF123\"}", 0));

        ReplayService.ReplayResult result = service.replayById(11L, null, true, 9L, "127.0.0.1");

        assertThat(result.items()).hasSize(1);
        ReplayService.ReplayItem item = result.items().get(0);
        assertThat(item.skipped()).isFalse();
        assertThat(item.replayStatus()).isEqualTo("SUCCESS");
        assertThat(item.originalStatus()).isEqualTo("SUCCESS");
        assertThat(item.dataJson()).contains("已签收");
        assertThat(item.paramsJson()).contains("SF123");
        verify(auditLogService).record(any(), anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("干跑护栏：非只读工具被跳过并说明原因（不制造副作用）")
    void shouldSkipNonReadOnlyToolInDryRun() {
        Tool writeTool = mock(Tool.class);
        when(writeTool.name()).thenReturn("manage_pet_profile");
        when(writeTool.readOnly()).thenReturn(false);
        when(registry.find("manage_pet_profile")).thenReturn(Optional.of(writeTool));
        when(mapper.selectById(12L)).thenReturn(log(12L, "manage_pet_profile", "{\"pet_name\":\"旺财\"}", 0));

        ReplayService.ReplayResult result = service.replayById(12L, null, true, 9L, "127.0.0.1");

        ReplayService.ReplayItem item = result.items().get(0);
        assertThat(item.skipped()).isTrue();
        assertThat(item.skipReason()).isEqualTo("NOT_READ_ONLY");
        assertThat(item.message()).contains("干跑模式跳过非只读工具");
        verify(writeTool, org.mockito.Mockito.never()).execute(any(), any());
    }

    @Test
    @DisplayName("关闭干跑：非只读工具实际执行（显式授权才产生副作用）")
    void shouldExecuteNonReadOnlyWhenDryRunDisabled() {
        Tool writeTool = mock(Tool.class);
        when(writeTool.name()).thenReturn("manage_pet_profile");
        when(writeTool.readOnly()).thenReturn(false);
        when(writeTool.execute(any(ToolContext.class), any(JsonNode.class)))
                .thenReturn(ToolResult.success(JsonUtils.readTree("{\"id\":7}"), 2L));
        when(registry.find("manage_pet_profile")).thenReturn(Optional.of(writeTool));
        when(mapper.selectById(12L)).thenReturn(log(12L, "manage_pet_profile", "{\"pet_name\":\"旺财\"}", 0));

        ReplayService.ReplayResult result = service.replayById(12L, null, false, 9L, "127.0.0.1");

        assertThat(result.dryRun()).isFalse();
        assertThat(result.items().get(0).skipped()).isFalse();
        verify(writeTool).execute(any(), any());
    }

    @Test
    @DisplayName("工具已下线（未注册）→ 标记 TOOL_NOT_FOUND，不执行")
    void shouldMarkMissingTool() {
        when(registry.find("ghost_tool")).thenReturn(Optional.empty());
        when(registry.names()).thenReturn(java.util.Set.of("query_express"));
        when(mapper.selectById(13L)).thenReturn(log(13L, "ghost_tool", "{}", 1));

        ReplayService.ReplayResult result = service.replayById(13L, null, true, 9L, "127.0.0.1");

        assertThat(result.items().get(0).skipped()).isTrue();
        assertThat(result.items().get(0).errorType()).isEqualTo("TOOL_NOT_FOUND");
    }

    @Test
    @DisplayName("AC② 复现拦截：模型声称查到快递，但历史记录为失败 → 拦截")
    void shouldReproduceInterception() {
        when(registry.find("query_express")).thenReturn(Optional.empty());
        when(mapper.selectById(21L)).thenReturn(log(21L, "query_express", "{}", ToolStatus.FAILED.getCode()));

        ReplayService.ReplayResult result = service.replayById(21L, CLAIMING_REPLY, true, 9L, "127.0.0.1");

        assertThat(result.consistency().replayed()).isTrue();
        assertThat(result.consistency().intercepted()).isTrue();
        assertThat(result.consistency().claimText()).isNotBlank();
        assertThat(result.consistency().reason()).isEqualTo("CLAIM_UNSUPPORTED");
    }

    @Test
    @DisplayName("AC② 反向：历史记录为成功 → 不拦截（复现结论与事实一致）")
    void shouldNotInterceptWhenHistorySucceeded() {
        when(registry.find("query_express")).thenReturn(Optional.empty());
        when(mapper.selectById(22L)).thenReturn(log(22L, "query_express", "{}", ToolStatus.SUCCESS.getCode()));

        ReplayService.ReplayResult result = service.replayById(22L, CLAIMING_REPLY, true, 9L, "127.0.0.1");

        assertThat(result.consistency().replayed()).isTrue();
        assertThat(result.consistency().intercepted()).isFalse();
    }

    @Test
    @DisplayName("未给回复文本 → 不做一致性复现（replayed=false）")
    void shouldSkipConsistencyWithoutReply() {
        when(registry.find("query_express")).thenReturn(Optional.empty());
        when(mapper.selectById(23L)).thenReturn(log(23L, "query_express", "{}", 0));

        ReplayService.ReplayResult result = service.replayById(23L, "  ", true, 9L, "127.0.0.1");

        assertThat(result.consistency().replayed()).isFalse();
    }

    @Test
    @DisplayName("整条链路回放：按 traceId 重跑全部调用")
    void shouldReplayWholeTrace() {
        when(registry.find(anyString())).thenReturn(Optional.empty());
        when(mapper.selectList(any())).thenReturn(List.of(
                log(31L, "query_express", "{}", 0),
                log(32L, "plan_route", "{}", 0)));

        ReplayService.ReplayResult result =
                service.replayByTrace(TRACE, CLAIMING_REPLY, true, 9L, "127.0.0.1");

        assertThat(result.traceId()).isEqualTo(TRACE);
        assertThat(result.items()).hasSize(2);
        assertThat(result.openid()).contains("****");
    }

    @Test
    @DisplayName("日志不存在 / traceId 无记录 → 明确拒绝")
    void shouldRejectMissingLog() {
        when(mapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> service.replayById(404L, null, true, 9L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("工具日志不存在");

        when(mapper.selectList(any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.replayByTrace("trace-none", null, true, 9L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("无工具调用记录");
    }

    private static ToolCallLogEntity log(Long id, String toolName, String paramsJson, int status) {
        ToolCallLogEntity entity = new ToolCallLogEntity();
        entity.setId(id);
        entity.setTraceId(TRACE);
        entity.setOpenid("openid-abcdefgh1234");
        entity.setSessionId(1L);
        entity.setToolName(toolName);
        entity.setCallSeq(1);
        entity.setParamsJson(paramsJson);
        entity.setResultJson("{}");
        entity.setStatus(status);
        entity.setLatencyMs(5);
        entity.setLlmRound(0);
        return entity;
    }
}
