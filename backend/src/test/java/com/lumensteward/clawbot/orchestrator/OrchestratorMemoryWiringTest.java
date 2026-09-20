package com.lumensteward.clawbot.orchestrator;

import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.context.HeuristicTokenEstimator;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.memory.MemoryGrowthNotice;
import com.lumensteward.clawbot.application.memory.MemoryRecallService;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestratorImpl;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmTimeoutException;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人状态库与编排链路的接线测试（迭代 4 W6：召回注入 + 生长发布）。
 *
 * <p>验证的是「两头接上」这一 W6 最容易漏的部分：
 * <ul>
 *   <li><b>召回：</b>长期记忆以 system 块进入<b>这一次</b>模型请求的消息序列；服务返回 null 时
 *       不塞空块；</li>
 *   <li><b>生长：</b>仅在链路<b>成功产出终态回复</b>后发布 {@link MemoryGrowthNotice}
 *       （携带 openid/会话/链路溯源）；走降级时不发布——兜底文案没有可抽取的事实。</li>
 * </ul>
 */
class OrchestratorMemoryWiringTest {

    private static final String OPENID = "openid-mem";
    private static final String RECALL_BLOCK = "以下是该用户此前对话中沉淀的长期信息\n- [人物] 妈妈：住在北京";

    private final LlmClient llm = mock(LlmClient.class);
    private final MemoryRecallService recallService = mock(MemoryRecallService.class);
    private final RecordingPublisher publisher = new RecordingPublisher();

    private final ConsistencyChecker passChecker =
            (reply, executed) -> ConsistencyVerdict.pass();
    private final ContentSafetyService passSafety = text -> SafetyVerdict.pass();

    private AgentOrchestratorImpl build() {
        OrchestrationProperties orchestration = new OrchestrationProperties(3, 3, 25000, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        return new AgentOrchestratorImpl(llm, new ToolRegistry(List.of()),
                mock(ContextStore.class), new ContextTrimmer(new HeuristicTokenEstimator()),
                passChecker, passSafety, new DefaultFallbackService(),
                mock(ToolCallLogService.class), orchestration, llmProperties,
                null, null, null, publisher, null, null, recallService);
    }

    private List<ChatMessage> firstRequestMessages() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm, atLeastOnce()).chat(captor.capture());
        return captor.getValue().messages();
    }

    @Test
    @DisplayName("召回块作为 system 消息进入本次请求（排在历史之前、用户消息之后仍有当前问题）")
    void injectsRecallBlockAsSystemMessage() {
        when(recallService.buildRecallBlock(OPENID)).thenReturn(RECALL_BLOCK);
        when(llm.chat(any())).thenReturn(ChatResult.text("你好呀"));

        build().run(new OrchestrationRequest("trace-mem-1", OPENID, 11L, "最近还好吗", List.of()));

        List<ChatMessage> messages = firstRequestMessages();
        List<String> systemBlocks = messages.stream()
                .filter(m -> ChatMessage.ROLE_SYSTEM.equals(m.role()))
                .map(ChatMessage::content).toList();
        assertThat(systemBlocks).contains(RECALL_BLOCK);
        assertThat(messages.get(messages.size() - 1).role()).isEqualTo(ChatMessage.ROLE_USER);
        assertThat(messages.get(messages.size() - 1).content()).isEqualTo("最近还好吗");
    }

    @Test
    @DisplayName("无长期记忆时不注入空 system 块（不污染上下文）")
    void doesNotInjectWhenNoMemory() {
        when(recallService.buildRecallBlock(OPENID)).thenReturn(null);
        when(llm.chat(any())).thenReturn(ChatResult.text("你好呀"));

        build().run(new OrchestrationRequest("trace-mem-2", OPENID, 12L, "你好", List.of()));

        assertThat(firstRequestMessages().stream().map(ChatMessage::content).toList())
                .noneMatch(RECALL_BLOCK::equals);
    }

    @Test
    @DisplayName("成功回复后发布生长事件，携带 openid/会话/链路与双方原文")
    void publishesGrowthNoticeAfterSuccess() {
        when(llm.chat(any())).thenReturn(ChatResult.text("好嘞，我记住了"));

        OrchestrationResult result = build().run(
                new OrchestrationRequest("trace-mem-3", OPENID, 13L, "我妈住在北京", List.of()));

        assertThat(result.replyText()).isEqualTo("好嘞，我记住了");
        assertThat(publisher.growthNotices()).singleElement().satisfies(notice -> {
            assertThat(notice.openid()).isEqualTo(OPENID);
            assertThat(notice.sessionId()).isEqualTo(13L);
            assertThat(notice.traceId()).isEqualTo("trace-mem-3");
            assertThat(notice.userMessage()).isEqualTo("我妈住在北京");
            assertThat(notice.assistantReply()).isEqualTo("好嘞，我记住了");
            assertThat(notice.worthExtracting()).isTrue();
        });
    }

    @Test
    @DisplayName("链路降级（LLM 超时）不发布生长事件：兜底文案无可抽取事实")
    void doesNotPublishOnFallback() {
        when(llm.chat(any())).thenThrow(new LlmTimeoutException("timeout"));

        OrchestrationResult result = build().run(
                new OrchestrationRequest("trace-mem-4", OPENID, 14L, "帮我查快递", List.of()));

        assertThat(result.degraded()).isTrue();
        assertThat(publisher.growthNotices()).isEmpty();
    }

    /** 记录型发布器：只关心状态库生长事件。 */
    static class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        List<MemoryGrowthNotice> growthNotices() {
            return events.stream()
                    .filter(MemoryGrowthNotice.class::isInstance)
                    .map(MemoryGrowthNotice.class::cast)
                    .toList();
        }
    }
}
