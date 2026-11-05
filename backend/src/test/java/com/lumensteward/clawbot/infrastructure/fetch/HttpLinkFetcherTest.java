package com.lumensteward.clawbot.infrastructure.fetch;

import com.lumensteward.clawbot.domain.port.LinkFetchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 链接抓取器 SSRF 护栏测试（迭代 4 W16）：地址校验是<b>纯静态</b>判定，离线可证。
 * 成功抓取路径以 ReadLinkToolTest 的端口桩与本机冷启动的外网取证为准（沙箱出网受限时如实标注）。
 */
class HttpLinkFetcherTest {

    @Test
    @DisplayName("协议白名单：http/https 放行；file/ftp/未知协议拒绝")
    void shouldRejectNonHttpSchemes() {
        assertThatCode(() -> HttpLinkFetcher.validate("https://example.com/page")).doesNotThrowAnyException();
        assertThatCode(() -> HttpLinkFetcher.validate("http://example.com/page")).doesNotThrowAnyException();

        for (String bad : new String[]{"file:///etc/passwd", "ftp://example.com/a",
                "gopher://x", "javascript:alert(1)", "example.com/no-scheme"}) {
            assertThatThrownBy(() -> HttpLinkFetcher.validate(bad))
                    .as("非 http(s) 地址应被拒: %s", bad)
                    .isInstanceOf(LinkFetchException.class);
        }
    }

    @Test
    @DisplayName("端口白名单：仅 80/443；8080/元数据端口拒绝")
    void shouldRejectNonStandardPorts() {
        assertThatCode(() -> HttpLinkFetcher.validate("https://example.com:443/a")).doesNotThrowAnyException();
        assertThatCode(() -> HttpLinkFetcher.validate("http://example.com:80/a")).doesNotThrowAnyException();

        assertThatThrownBy(() -> HttpLinkFetcher.validate("http://example.com:8080/a"))
                .isInstanceOf(LinkFetchException.class);
        assertThatThrownBy(() -> HttpLinkFetcher.validate("http://example.com:8443/a"))
                .isInstanceOf(LinkFetchException.class);
    }

    @Test
    @DisplayName("私网拒绝（SSRF）：字面量环回/站点本地/链路本地地址全部拦截")
    void shouldRejectPrivateAndReservedAddresses() {
        for (String bad : new String[]{
                "http://127.0.0.1/x", "http://[::1]/x", "http://10.1.2.3/x",
                "http://192.168.1.1/x", "http://172.16.0.9/x", "http://169.254.169.254/latest/meta-data",
                "http://0.0.0.0/x"}) {
            assertThatThrownBy(() -> HttpLinkFetcher.validate(bad))
                    .as("私网/保留地址应被拒: %s", bad)
                    .isInstanceOf(LinkFetchException.class)
                    .hasMessageContaining("私网");
        }
    }

    @Test
    @DisplayName("本机主机名（localhost）解析到环回 → 拒绝；缺主机名 → 拒绝")
    void shouldRejectLoopbackHostnameAndMissingHost() {
        assertThatThrownBy(() -> HttpLinkFetcher.validate("http://localhost/x"))
                .isInstanceOf(LinkFetchException.class)
                .hasMessageContaining("私网");
        assertThatThrownBy(() -> HttpLinkFetcher.validate("http:///no-host"))
                .isInstanceOf(LinkFetchException.class);
        assertThatThrownBy(() -> HttpLinkFetcher.validate(null))
                .isInstanceOf(LinkFetchException.class);
    }
}
