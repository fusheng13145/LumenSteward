package com.lumensteward.clawbot.e2e;

import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import com.lumensteward.clawbot.infrastructure.security.JwtTokenProvider;
import com.lumensteward.clawbot.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D5 闭环集成验证（真实 MySQL）：{@code OPERATOR 令牌 → 调配置写接口 → 403 → 落 log_audit}。
 *
 * <p>D5 已实现 {@code RestAccessDeniedHandler} 在返回 403 时写越权审计，但此前缺少「真实链路 +
 * 真实落库」的闭环证据。本用例在<b>真实 Spring 上下文</b>（Testcontainers MySQL 8 + Redis 7，
 * 属性由 {@link TestcontainersConfig} 注入）中以 {@code MockMvc} 走通：
 * <ol>
 *   <li>为 OPERATOR 角色签发真实 JWT（{@link JwtTokenProvider#issue}）；</li>
 *   <li>携带该令牌 {@code PUT /api/configs}（合法请求体，确保校验通过、真正抵达
 *       {@code @PreAuthorize("hasRole('SUPER_ADMIN')")} 授权判定）；</li>
 *   <li>断言 HTTP 403 + 响应体 {@code code=20003}；</li>
 *   <li>断言 {@code log_audit} 新增一行：{@code reg_type=AUTH}、{@code action=ACCESS_DENIED}、
 *       {@code result=0}、{@code target} 指向被拒 URI——即越权尝试可被追溯。</li>
 * </ol>
 *
 * <p>标注 {@code @Tag("integration")}（Testcontainers / Docker 前置）；以 {@code -Dgroups=integration} 运行。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AccessDeniedAuditIntegrationTest extends TestcontainersConfig {

    private static final String OPERATOR_USERNAME = "it-operator";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("D5：OPERATOR 调配置写 → 403(code=20003) 且落 log_audit(AUTH/ACCESS_DENIED/result=0)")
    void operatorConfigWriteIsForbiddenAndAudited() throws Exception {
        Long operatorId = seedOperator();
        String token = jwtTokenProvider.issue(operator(operatorId));
        String body = "{\"reason\":\"D5 越权审计闭环验证\",\"items\":["
                + "{\"configKey\":\"llm.model\",\"configValue\":\"mock-model\"}]}";

        int auditsBefore = countDeniedAudits();

        mockMvc.perform(put("/api/configs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(20003));

        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM log_audit "
                        + "WHERE reg_type = 'AUTH' AND action = 'ACCESS_DENIED' AND result = 0 "
                        + "AND target LIKE '%/api/configs%'",
                Integer.class);
        assertThat(auditRows)
                .as("越权被拒后必须落审计（D5 闭环）")
                .isNotNull()
                .isEqualTo(auditsBefore + 1);

        Long storedAdminId = jdbcTemplate.queryForObject(
                "SELECT admin_id FROM log_audit "
                        + "WHERE reg_type = 'AUTH' AND action = 'ACCESS_DENIED' "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class);
        assertThat(storedAdminId).as("审计应记录操作人（OPERATOR 的 adminId）").isEqualTo(operatorId);

        System.out.println("[D5-EVIDENCE] OPERATOR PUT /api/configs -> HTTP 403 code=20003");
        System.out.println("[D5-EVIDENCE] log_audit reg_type=AUTH action=ACCESS_DENIED result=0 "
                + "admin_id=" + storedAdminId + " target=/api/configs");
    }

    private Long seedOperator() {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_admin_user WHERE username = ?", Integer.class, OPERATOR_USERNAME);
        if (existing != null && existing > 0) {
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM sys_admin_user WHERE username = ?", Long.class, OPERATOR_USERNAME);
        }
        jdbcTemplate.update(
                "INSERT INTO sys_admin_user (username, password_hash, display_name, role, status, fail_count) "
                        + "VALUES (?, ?, ?, 'OPERATOR', 1, 0)",
                OPERATOR_USERNAME, "__ENV_INJECTED__", "IT Operator");
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_admin_user WHERE username = ?", Long.class, OPERATOR_USERNAME);
    }

    private int countDeniedAudits() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM log_audit "
                        + "WHERE reg_type = 'AUTH' AND action = 'ACCESS_DENIED' AND result = 0 "
                        + "AND target LIKE '%/api/configs%'",
                Integer.class);
        return count == null ? 0 : count;
    }

    private static SysAdminUserEntity operator(Long id) {
        SysAdminUserEntity admin = new SysAdminUserEntity();
        admin.setId(id);
        admin.setUsername(OPERATOR_USERNAME);
        admin.setRole("OPERATOR");
        admin.setDisplayName("IT Operator");
        admin.setStatus(1);
        return admin;
    }
}
