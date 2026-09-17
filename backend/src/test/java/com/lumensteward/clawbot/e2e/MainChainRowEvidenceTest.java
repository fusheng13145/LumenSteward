package com.lumensteward.clawbot.e2e;

import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import com.lumensteward.clawbot.support.TestcontainersConfig;
import com.lumensteward.clawbot.support.WechatSignatureGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主链路「真实落库」端到端集成证据（D1/D3/D4 / AC-A1 / AC-A7 / AC-B6 / AC-E5）。
 *
 * <p><b>前置（故标注 {@code integration}，由 surefire {@code excludedGroups} 排除）：</b>
 * Docker（Testcontainers 自包含启动 MySQL 8 与 Redis 7；数据源 / Redis / Flyway 属性由
 * {@link TestcontainersConfig} 的 {@code @DynamicPropertySource} 统一注入，<b>不再依赖外部服务</b>）。
 *
 * <p>可执行的组件级等价证据另见 {@code verification/MainChainWiringVerificationTest}
 * （以内存替身断言 user+assistant 落库与编排器接线）。
 *
 * <p>用例以真实签名触发回调（content 含"叫"，经 Mock LLM 脚本触发 {@code manage_pet_profile}
 * 真实工具调用），随后断言：
 * <ol>
 *   <li>{@code wx_message}：同一 openid 同时存在 {@code user} 与 {@code assistant} 两行（主链路已产出并落库终态）；</li>
 *   <li>{@code wx_user}：首交互用户被 upsert（AC-E5）；</li>
 *   <li>{@code log_tool_call}：存在工具调用记录且 {@code call_seq} 自 1 起（AC-B6/B7）；</li>
 *   <li>{@code log_tool_call.trace_id}：落库值完整 36 位（D7 列宽修复的直接实证）。</li>
 * </ol>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MainChainRowEvidenceTest extends TestcontainersConfig {

    private static final long AWAIT_TIMEOUT_MS = 5000L;

    @Autowired
    private WechatCallbackController controller;

    @Autowired
    private WechatProperties wechatProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("主链路真实落库：wx_message(user+assistant) + wx_user upsert + log_tool_call(call_seq=1)")
    void callbackShouldPersistUserAssistantAndToolLog() throws InterruptedException {
        String openid = "openid-it-mainchain-001";
        String timestamp = WechatSignatureGenerator.nowTimestamp();
        String nonce = "it-nonce";
        String signature = WechatSignatureGenerator.sign(wechatProperties.token(), timestamp, nonce);

        // content 含"叫" → Mock LLM 脚本触发 manage_pet_profile 真实调用（log_tool_call 第 0 轮）
        String body = "<xml><FromUserName><![CDATA[" + openid + "]]></FromUserName>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<MsgId>it-mainchain-msg-1</MsgId>"
                + "<Content><![CDATA[我家新养了只猫，叫咪咪]]></Content></xml>";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setParameter("msg_type", "text");

        String response = controller.receive(signature, timestamp, nonce, null, body, request);
        assertThat(response).isNotBlank();

        // 终态可能经「后推送」异步落库：轮询等待 assistant 行出现（最长 AWAIT_TIMEOUT_MS）
        awaitAssistantRow(openid);

        List<String> roles = jdbcTemplate.queryForList(
                "SELECT role FROM wx_message WHERE openid = ?", String.class, openid);
        assertThat(roles).as("主链路应产出并落库 user + assistant 两条消息").contains("user", "assistant");

        Integer users = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wx_user WHERE openid = ?", Integer.class, openid);
        assertThat(users).as("首交互用户应被 upsert（AC-E5）").isNotNull().isGreaterThanOrEqualTo(1);

        List<Integer> seqs = jdbcTemplate.queryForList(
                "SELECT call_seq FROM log_tool_call WHERE openid = ? ORDER BY call_seq", Integer.class, openid);
        assertThat(seqs).as("应存在工具调用记录（AC-B6/B7）").isNotEmpty();
        assertThat(seqs.get(0)).as("call_seq 应自 1 起（D3 修复）").isEqualTo(1);

        // D7 直接实证：trace_id 落库值必须完整（36 位 UUID），证明确实写入成功而非被列宽截断/静默丢弃
        String traceId = jdbcTemplate.queryForObject(
                "SELECT trace_id FROM log_tool_call WHERE openid = ? ORDER BY call_seq LIMIT 1", String.class, openid);
        assertThat(traceId)
                .as("D7：trace_id 应为完整 36 位 UUID（VARCHAR(64) 修复后不再 Data too long）")
                .isNotNull()
                .hasSize(36)
                .matches("^[0-9a-fA-F-]{36}$");

        Integer totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM log_tool_call WHERE openid = ?", Integer.class, openid);
        assertThat(totalRows).as("D7：log_tool_call 运行期不得恒空").isNotNull().isPositive();

        dumpEvidence(openid, roles, users, seqs, traceId, totalRows);
    }

    /** 输出 D7 实证所需的具体 DB 行（供构建日志留痕）。 */
    private void dumpEvidence(String openid, List<String> roles, Integer users, List<Integer> seqs,
                              String traceId, Integer totalRows) {
        System.out.println("[D7-EVIDENCE] openid=" + openid);
        System.out.println("[D7-EVIDENCE] wx_message.roles=" + roles);
        System.out.println("[D7-EVIDENCE] wx_user.count=" + users);
        System.out.println("[D7-EVIDENCE] log_tool_call.rows=" + totalRows);
        System.out.println("[D7-EVIDENCE] log_tool_call.call_seq=" + seqs);
        System.out.println("[D7-EVIDENCE] log_tool_call.trace_id=" + traceId
                + " (len=" + (traceId == null ? 0 : traceId.length()) + ")");
        List<String> traceIds = jdbcTemplate.queryForList(
                "SELECT trace_id FROM log_tool_call WHERE openid = ? ORDER BY call_seq", String.class, openid);
        System.out.println("[D7-EVIDENCE] log_tool_call.trace_id(all)=" + traceIds);
    }

    private void awaitAssistantRow(String openid) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            Integer assistants = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM wx_message WHERE openid = ? AND role = 'assistant'",
                    Integer.class, openid);
            if (assistants != null && assistants > 0) {
                return;
            }
            Thread.sleep(100L);
        }
    }
}
