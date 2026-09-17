package com.lumensteward.clawbot.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 内容安全（Safety）配置（{@code safety.*}，G-16；架构 5.4）。
 *
 * <p>BR-12：内容安全不可用时默认 Fail-Closed（{@code failClosed=true}）；
 * {@code strictMode} 对应 9.4.5 的严格校验模式。
 *
 * @param wordlistPath 本地敏感词库路径（classpath 或文件路径，T04 落地）
 * @param failClosed   安全服务不可用时的失败策略：true=拒绝（Fail-Closed）
 * @param strictMode   严格模式开关（9.4.5）
 */
@ConfigurationProperties(prefix = "safety")
public record SafetyProperties(
        @DefaultValue("classpath:wordlist/sensitive-words.txt") String wordlistPath,
        @DefaultValue("true") boolean failClosed,
        @DefaultValue("false") boolean strictMode) {
}
