package com.lumensteward.clawbot.infrastructure.config;

import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 微信通道配置（G-16 Bean 声明收敛）。
 *
 * <p>装配微信服务调用所需的 {@link RestClient}（复用 {@code HttpClientConfig} 提供、已带超时与
 * traceId 透传的 {@link RestClient.Builder}）。T03 的 {@code WechatTransportFactory} 将据此在
 * {@code MockWechatTransport} / {@code RealWechatTransport} 间按 {@code wx.mock-enabled} 切换
 * （AC-D3 零代码改动）。
 *
 * <p>此处不发起任何网络调用，仅声明 Bean；连通性由 {@code StartupDoctor}（SUP-05）在启动/手动触发时探测。
 */
@Configuration
public class WechatConfig {

    /** 微信通道配置（构造器注入，G-14）。 */
    private final WechatProperties properties;

    /**
     * 构造器注入。
     *
     * @param properties 微信通道配置
     */
    public WechatConfig(WechatProperties properties) {
        this.properties = properties;
    }

    /**
     * 微信开放接口专用出站客户端。
     *
     * @param builder 共享的 RestClient.Builder（含超时与 traceId 透传）
     * @return 微信通道 RestClient
     */
    @Bean
    public RestClient wechatRestClient(RestClient.Builder builder) {
        return builder.clone().build();
    }

    /**
     * 当前微信通道模式（供日志/自检读取，不改变 Bean 装配）。
     *
     * @return {@code MOCK} 或 {@code REAL}
     */
    public String transportMode() {
        return properties.mockEnabled() ? "MOCK" : "REAL";
    }
}
