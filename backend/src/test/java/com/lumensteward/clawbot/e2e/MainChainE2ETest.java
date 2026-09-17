package com.lumensteward.clawbot.e2e;

import com.lumensteward.clawbot.support.TestcontainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主链路端到端集成测试（AC-E2 / AC-E3 / FR-15）。
 *
 * <p><b>前置（故标注 {@code integration}，由 surefire {@code excludedGroups} 排除）：</b>
 * <ul>
 *   <li>Docker：提供 Testcontainers MySQL 8；</li>
 *   <li>Redis：默认 {@code localhost:6379}（可用 {@code REDIS_HOST}/{@code REDIS_PORT} 覆盖）。</li>
 * </ul>
 *
 * <p>完整可执行走查（含合法签名发消息、宠物登记、登录成功）见 {@code scripts/e2e-smoke.ps1}；
 * 本类聚焦「无凭据即可断言」的访问语义，作为 CI 回归网。
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MainChainE2ETest extends TestcontainersConfig {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("AC-E2：未携带 Token 访问受保护接口 → 401（不是 200）")
    void protectedEndpointWithoutTokenShouldReturn401() {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/users", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":20001");
    }

    @Test
    @DisplayName("AC-E1：错误凭据登录 → 401（不区分账号存在性）")
    void loginWithBadCredentialsShouldReturn401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of("username", "superadmin", "password", "definitely-wrong");

        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/login",
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).contains("\"code\":20005");
    }

    @Test
    @DisplayName("AC-A2：伪造签名的微信回调被拒绝（非 2xx）")
    void forgedWechatSignatureShouldBeRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        String xml = "<xml><ToUserName><![CDATA[gh]]></ToUserName>"
                + "<FromUserName><![CDATA[onotreal]]></FromUserName>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[hi]]></Content></xml>";

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/wx/callback?signature=deadbeef&timestamp=1&nonce=x",
                HttpMethod.POST, new HttpEntity<>(xml, headers), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    @DisplayName("AC-A4：GET echostr 使用错误签名 → 非 200 成功")
    void echostrWithWrongSignatureShouldFail() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/wx/callback?signature=deadbeef&timestamp=1&nonce=x&echostr=hello", String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }
}
