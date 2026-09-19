package com.lumensteward.clawbot.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 调度配置（FR-19 定时清理任务启用）。
 *
 * <p>仅开启 Spring 调度能力；具体任务由 {@code @Scheduled} 标注的 Service 承载（G-16 配置类收敛）。
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
