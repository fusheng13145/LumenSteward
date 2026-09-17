package com.lumensteward.clawbot.infrastructure.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.infrastructure.config.properties.AdminBootstrapProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.SysAdminUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 初始管理员引导（Q7 / BR-20，架构 3.5）。
 *
 * <p>{@link ApplicationRunner}，{@code @Order(1)}，早于 Startup Doctor 的展示时机。逻辑：
 * <ol>
 *   <li>定位初始管理员（默认 {@code superadmin}）；</li>
 *   <li>若其 {@code password_hash} 仍为占位标记 {@link AdminInitializer#PLACEHOLDER_HASH}，
 *       则经 {@link AdminInitializer} 从环境变量取得明文口令并计算 BCrypt 覆写；</li>
 *   <li>非占位（已注入）或无记录时跳过，保证幂等。</li>
 * </ol>
 */
@Component
@Order(1)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final SysAdminUserMapper sysAdminUserMapper;
    private final AdminInitializer adminInitializer;
    private final AdminBootstrapProperties adminBootstrapProperties;

    /**
     * 构造器注入（G-14）。
     *
     * @param sysAdminUserMapper      管理员 Mapper
     * @param adminInitializer        口令注入器
     * @param adminBootstrapProperties 初始管理员引导配置
     */
    public AdminBootstrapRunner(SysAdminUserMapper sysAdminUserMapper,
                                AdminInitializer adminInitializer,
                                AdminBootstrapProperties adminBootstrapProperties) {
        this.sysAdminUserMapper = sysAdminUserMapper;
        this.adminInitializer = adminInitializer;
        this.adminBootstrapProperties = adminBootstrapProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String username = adminBootstrapProperties.username();
        SysAdminUserEntity admin = sysAdminUserMapper.selectOne(
                new LambdaQueryWrapper<SysAdminUserEntity>()
                        .eq(SysAdminUserEntity::getUsername, username)
                        .last("LIMIT 1"));

        if (admin == null) {
            log.info("未发现初始管理员[{}]，跳过口令注入", username);
            return;
        }
        if (!AdminInitializer.PLACEHOLDER_HASH.equals(admin.getPasswordHash())) {
            log.info("初始管理员[{}]口令已注入，跳过", username);
            return;
        }

        String rawPassword = adminInitializer.resolveInitialPassword();
        admin.setPasswordHash(adminInitializer.encode(rawPassword));
        sysAdminUserMapper.updateById(admin);
        log.info("初始管理员[{}]口令已从环境变量注入（BCrypt）", username);
    }
}
