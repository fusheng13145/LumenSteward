package com.lumensteward.clawbot.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * D7 真实 MySQL 实证：长标识列宽修复（{@code log_tool_call.trace_id}）。
 *
 * <p><b>背景：</b>原 {@code trace_id VARCHAR(32)} 容纳不下 36 位 UUID，INSERT 恒报
 * {@code Data too long for column 'trace_id'}，被 best-effort 逻辑静默吞掉 → {@code log_tool_call}
 * 运行期恒为空表。{@code V1.0.5__fix_long_identifier_columns.sql} 以增量方式加宽至 {@code VARCHAR(64)}。
 *
 * <p>本用例在 Testcontainers MySQL 8 上执行<b>真实 Flyway 迁移</b>后，直接以 JDBC 断言：
 * <ol>
 *   <li>{@code information_schema} 中 {@code log_tool_call.trace_id} 的字符长度上限为 64；</li>
 *   <li>插入一行 36 位 UUID 的 {@code trace_id} <b>成功</b>（不再 Data too long）；</li>
 *   <li>读回的 {@code trace_id} <b>完整 36 位</b>（未截断）；</li>
 *   <li>同一 openid 下 {@code call_seq} 可连续自 1 递增写库（AC-B6/B7 的事实基础）。</li>
 * </ol>
 *
 * <p>标注 {@code @Tag("integration")}：由 surefire {@code excludedGroups} 排除（本地无 Docker 时）；
 * 以 {@code -Dgroups=integration} 运行。仅用 {@link TestcontainersConfig#dataSource()}，不启动 Spring 上下文。
 */
@Tag("integration")
class LongIdentifierColumnMigrationTest extends TestcontainersConfig {

    private static final String TRACE_ID = UUID.randomUUID().toString();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    @DisplayName("D7：trace_id 列宽为 VARCHAR(64)（不再是 32）")
    void traceIdColumnIsWidened() throws SQLException {
        try (Connection connection = dataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT character_maximum_length, data_type, is_nullable "
                             + "FROM information_schema.columns "
                             + "WHERE table_schema = DATABASE() AND table_name = 'log_tool_call' AND column_name = 'trace_id'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("log_tool_call.trace_id 列应存在").isTrue();
                int maxLength = resultSet.getInt("character_maximum_length");
                assertThat(maxLength)
                        .as("D7：trace_id 长度上限应加宽至 64").isEqualTo(64);
                assertThat(resultSet.getString("data_type")).isEqualToIgnoringCase("varchar");
                assertThat(resultSet.getString("is_nullable")).isEqualToIgnoringCase("NO");
                System.out.println("[D7-EVIDENCE] information_schema log_tool_call.trace_id: "
                        + "data_type=varchar max_length=" + maxLength + " is_nullable=NO");
            }
        }
    }

    @Test
    @DisplayName("D7：36 位 UUID 可写入并完整读回；call_seq 自 1 递增")
    void persistsFullUuidTraceIdAndIncrementingCallSeq() throws SQLException {
        assertThat(TRACE_ID).hasSize(36);

        try (Connection connection = dataSource().getConnection()) {
            long sessionId = insertSession(connection, "openid-d7-col");
            assertThatCode(() -> insertToolCall(connection, TRACE_ID, "openid-d7-col", sessionId, 1))
                    .as("36 位 UUID trace_id 必须可写入（V1.0.5 修复前此处必报 Data too long）")
                    .doesNotThrowAnyException();
            insertToolCall(connection, TRACE_ID, "openid-d7-col", sessionId, 2);

            assertThat(readTraceId(connection, "openid-d7-col"))
                    .as("读回的 trace_id 应为完整 36 位、未截断")
                    .isEqualTo(TRACE_ID)
                    .hasSize(36);

            java.util.List<Integer> seqs = readCallSeqs(connection, "openid-d7-col");
            assertThat(seqs)
                    .as("call_seq 应连续自 1 递增（AC-B6/B7）")
                    .containsExactly(1, 2);
            System.out.println("[D7-EVIDENCE] inserted trace_id=" + TRACE_ID + " (len=" + TRACE_ID.length() + ")");
            System.out.println("[D7-EVIDENCE] read-back trace_id=" + readTraceId(connection, "openid-d7-col"));
            System.out.println("[D7-EVIDENCE] log_tool_call.call_seq=" + seqs);
        }
    }

    private static long insertSession(Connection connection, String openid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO wx_session (openid, context_key, state, last_active_at) "
                        + "VALUES (?, ?, 'CHATTING', NOW())", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, openid);
            statement.setString(2, "conv:" + openid);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static void insertToolCall(Connection connection, String traceId, String openid,
                                       long sessionId, int callSeq) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO log_tool_call (trace_id, openid, session_id, tool_name, call_seq, "
                        + "params_json, status, latency_ms, llm_round) "
                        + "VALUES (?, ?, ?, 'manage_pet_profile', ?, '{}', 0, 12, 0)")) {
            statement.setString(1, traceId);
            statement.setString(2, openid);
            statement.setLong(3, sessionId);
            statement.setInt(4, callSeq);
            statement.executeUpdate();
        }
    }

    private static String readTraceId(Connection connection, String openid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT trace_id FROM log_tool_call WHERE openid = ? ORDER BY call_seq LIMIT 1")) {
            statement.setString(1, openid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static java.util.List<Integer> readCallSeqs(Connection connection, String openid) throws SQLException {
        java.util.List<Integer> seqs = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT call_seq FROM log_tool_call WHERE openid = ? ORDER BY call_seq")) {
            statement.setString(1, openid);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    seqs.add(resultSet.getInt(1));
                }
            }
        }
        return seqs;
    }
}
