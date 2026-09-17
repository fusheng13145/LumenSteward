package com.lumensteward.clawbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 应用唯一启动类。
 *
 * <p>设计要点：
 * <ul>
 *   <li>扫描根包 {@code com.lumensteward.clawbot}，覆盖 interfaces / application / domain /
 *       infrastructure / common 五层（SRS 9.2 分层单体）。</li>
 *   <li>{@link ConfigurationPropertiesScan} 注册 {@code infrastructure.config.properties} 下的
 *       {@code @ConfigurationProperties} 记录（Wechat/Llm/Orchestration/Security/Safety/AdminBootstrap），
 *       使各配置类得以构造器注入（T02）。</li>
 *   <li>MyBatis-Plus 的 Mapper 扫描交由 starter 自动完成（扫描启动类所在包的 {@code @Mapper}
 *       接口），T02 已为 8 个 Mapper 标注 {@code @Mapper}，无需额外 {@code @MapperScan}。</li>
 *   <li>微信回调与后台 JWT 过滤器链的隔离在 {@code SecurityConfig} 中完成（T02 骨架）。</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ClawbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClawbotApplication.class, args);
    }
}
