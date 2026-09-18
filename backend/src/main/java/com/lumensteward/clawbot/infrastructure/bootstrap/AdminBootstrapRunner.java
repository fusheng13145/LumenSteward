package com.lumensteward.clawbot.infrastructure.bootstrap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
 *   <li>定位所有口令仍为占位标记 {@link AdminInitializer#PLACEHOLDER_HASH} 的 seeded 管理员；</li>
 *   <li>以环境变量 {@code ADMIN_INIT_PASSWORD}（或 local 下生成的一次性口令）计算 BCrypt，
 *       对全部占位账号统一注入（superadmin 与 operator 等同享同一初始口令）；</li>
 *   <li>非占位（已注入）时无记录，跳过，保证幂等。</li>
 * </ol>
 */
@Component
@Order(1)
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final SysAdminUserMapper sysAdminUserMapper;
    private final AdminInitializer adminInitializer;

    /**
     * 构造器注入（G-14）。
     *
     * @param sysAdminUserMapper 管理员 Mapper
     * @param adminInitializer   口令注入器
     */
    public AdminBootstrapRunner(SysAdminUserMapper sysAdminUserMapper,
                                AdminInitializer adminInitializer) {
        this.sysAdminUserMapper = sysAdminUserMapper;
        this.adminInitializer = adminInitializer;
    }

    @Override
    public void run(ApplicationArguments args) {
        var placeholders = sysAdminUserMapper.selectList(
                new LambdaQueryWrapper<SysAdminUserEntity>()
                        .eq(SysAdminUserEntity::getPasswordHash, AdminInitializer.PLACEHOLDER_HASH));

        if (placeholders.isEmpty()) {
            log.info("未发现占位口令的初始管理员，跳过口令注入");
            return;
        }

        // 仅解析一次口令，确保所有占位账号共享同一初始口令（便于 e2e / 本地登录）
        String rawPassword = adminInitializer.resolveInitialPassword();
        String encoded = adminInitializer.encode(rawPassword);
        for (SysAdminUserEntity admin : placeholders) {
            admin.setPasswordHash(encoded);
            sysAdminUserMapper.updateById(admin);
        }
        log.info("已为 {} 个占位初始管理员注入口令（BCrypt）", placeholders.size());
    }
}
