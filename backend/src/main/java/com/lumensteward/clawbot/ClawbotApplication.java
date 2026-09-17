package com.lumensteward.clawbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用唯一启动类。
 *
 * <p>设计要点：
 * <ul>
 *   <li>扫描根包 {@code com.lumensteward.clawbot}，覆盖 interfaces / application / domain /
 *       infrastructure / common 五层（SRS 9.2 分层单体）。</li>
 *   <li>MyBatis-Plus 的 Mapper 扫描交由 starter 自动完成（扫描启动类所在包的 {@code @Mapper}
 *       接口）；T02 落地 Mapper 后无需在此额外声明 {@code @MapperScan}。</li>
 *   <li>微信回调与后台 JWT 过滤器链的隔离在 {@code SecurityConfig} 中完成（T02）。</li>
 * </ul>
 */
@SpringBootApplication
public class ClawbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClawbotApplication.class, args);
    }
}
