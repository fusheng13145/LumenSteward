package com.lumensteward.clawbot.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.lumensteward.clawbot.common.api.PageQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（G-16 配置类收敛 / G-17 分页拦截器）。
 *
 * <p><b>关键：</b>若未注册 {@link MybatisPlusInterceptor} + {@link PaginationInnerInterceptor}，
 * 分页参数会被<b>静默忽略</b>（不报错、返回全量数据），这是本项目历史上最易翻车的点。
 * 因此这里显式注册，并由 {@code MybatisPlusConfigTest} 以断言测试固化。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 注册 MyBatis-Plus 拦截器链，其中含分页内部拦截器。
     *
     * <p>{@code maxLimit} 与全局分页契约 {@link PageQuery#MAX_PAGE_SIZE} 对齐，
     * 超过上限时由拦截器兜底限制，避免大页查询拖垮数据库。
     *
     * @return MyBatis-Plus 拦截器
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大条数（与 8.2.1 分页契约一致）
        pagination.setMaxLimit((long) PageQuery.MAX_PAGE_SIZE);
        // 溢出总页数后不回到首页，交由前端按 total 自行处理
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
