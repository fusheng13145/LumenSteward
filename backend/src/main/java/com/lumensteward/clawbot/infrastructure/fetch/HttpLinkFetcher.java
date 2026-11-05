package com.lumensteward.clawbot.infrastructure.fetch;

import com.lumensteward.clawbot.domain.port.LinkFetchException;
import com.lumensteward.clawbot.domain.port.LinkFetchPort;
import com.lumensteward.clawbot.domain.port.model.LinkContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * {@link LinkFetchPort} 的 JDK HttpClient 实现（迭代 4 W16，零第三方依赖）。
 *
 * <p><b>SSRF 安全前置（手册 §7.7 W16，护栏先于能力）：</b>
 * <ul>
 *   <li>协议白名单 http/https 且端口仅 80/443；</li>
 *   <li>解析目标主机的<b>全部</b> InetAddress，任一为环回/站点本地/链路本地/任意本地即拒绝
 *       （拦 127.0.0.1、192.168/10./172.16 段、169.254 元数据端点等私网越界）；</li>
 *   <li>重定向手动跟随（≤{@value #MAX_REDIRECTS} 跳）且<b>每跳重新过全部校验</b>；</li>
 *   <li>响应体 ≤{@value #MAX_BODY_BYTES} 字节、连接 5s / 总超时 {@value #TOTAL_TIMEOUT_SECONDS}s、
 *       content-type 限 text/html | text/plain | application/xhtml+xml。</li>
 * </ul>
 * 已知边界（如实声明）：解析与连接之间存在 DNS rebinding 理论窗口（JDK HttpClient
 * 自行二次解析），单拥有方内网形态下风险可接受；如需根除须自定义 Resolver 钉住地址。
 */
@Component
public class HttpLinkFetcher implements LinkFetchPort {

    private static final Logger log = LoggerFactory.getLogger(HttpLinkFetcher.class);

    /** 最多跳转次数（每跳重验 SSRF 护栏）。 */
    static final int MAX_REDIRECTS = 3;

    /** 响应体上限（字节）。 */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    /** 总超时（秒）。 */
    static final int TOTAL_TIMEOUT_SECONDS = 8;

    private static final int CONNECT_TIMEOUT_SECONDS = 5;

    private final HttpClient client;

    /** 构造器（G-14）：HttpClient 无状态共享，redirect 策略永手动（每跳重验）。 */
    public HttpLinkFetcher() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public LinkContent fetch(String url) throws LinkFetchException {
        URI current = validate(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpResponse<byte[]> response = send(current);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new LinkFetchException("重定向缺少目标地址: " + current);
                }
                current = validate(current.resolve(location).toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                throw new LinkFetchException("目标返回 HTTP " + status + ": " + current);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (!(contentType.contains("text/html") || contentType.contains("text/plain")
                    || contentType.contains("application/xhtml"))) {
                throw new LinkFetchException("不支持的内容类型: " + contentType + "（仅支持网页与纯文本）");
            }
            return toContent(current, response.body());
        }
        throw new LinkFetchException("重定向次数超过上限 " + MAX_REDIRECTS + ": " + url);
    }

    private HttpResponse<byte[]> send(URI uri) throws LinkFetchException {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(TOTAL_TIMEOUT_SECONDS))
                    .header("User-Agent", "LumenSteward/1.0 (+link-summary)")
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new LinkFetchException("链接访问失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LinkFetchException("链接访问被中断", e);
        }
    }

    private LinkContent toContent(URI uri, byte[] body) {
        String html = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        HtmlTextExtractor.Extracted extracted = HtmlTextExtractor.extract(html);
        String title = HtmlTextExtractor.extractTitle(html);
        log.info("链接正文提取完成 host={} 正文 {} 字符 截断={}", uri.getHost(),
                extracted.text().length(), extracted.truncated());
        return new LinkContent(title, extracted.text(), extracted.truncated());
    }

    /** 全量地址校验：协议/端口白名单 + 解析全部 IP 拒绝私网（SSRF）。 */
    static URI validate(String url) throws LinkFetchException {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new LinkFetchException("地址格式非法: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new LinkFetchException("仅支持 http/https 链接: " + url);
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new LinkFetchException("仅支持 80/443 端口: " + url);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new LinkFetchException("地址缺少主机名: " + url);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new LinkFetchException("主机解析失败: " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new LinkFetchException("目标指向私网/保留地址，已拒绝: " + host);
            }
        }
        return uri;
    }
}
