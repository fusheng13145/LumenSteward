package com.lumensteward.clawbot.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.lumensteward.clawbot.common.api.PageQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页拦截器<b>生效断言</b>（G-17）。
 *
 * <p>动机：若未注册 {@link MybatisPlusInterceptor} + {@link PaginationInnerInterceptor}，
 * MyBatis-Plus 会<b>静默忽略</b>分页参数（不抛异常、返回全量数据）。本测试以断言固化该注册关系，
 * 一旦有人误删配置即失败，从而阻断回归。
 */
class MybatisPlusConfigTest {

    private final MybatisPlusConfig config = new MybatisPlusConfig();

    @Test
    @DisplayName("MybatisPlusInterceptor 中已注册 PaginationInnerInterceptor")
    void shouldRegisterPaginationInnerInterceptor() {
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();

        assertThat(interceptor.getInterceptors())
                .as("MybatisPlusInterceptor 必须包含分页内部拦截器，否则分页参数被静默忽略")
                .isNotEmpty()
                .anyMatch(inner -> inner instanceof PaginationInnerInterceptor);
    }

    @Test
    @DisplayName("分页拦截器方言为 MySQL 且单页上限与全局分页契约一致")
    void shouldConfigurePaginationDialectAndMaxLimit() {
        PaginationInnerInterceptor pagination = extractPagination(config.mybatisPlusInterceptor());

        assertThat(pagination.getDbType()).isEqualTo(DbType.MYSQL);
        assertThat(pagination.getMaxLimit()).isEqualTo((long) PageQuery.MAX_PAGE_SIZE);
        assertThat(pagination.isOverflow()).isFalse();
    }

    private static PaginationInnerInterceptor extractPagination(MybatisPlusInterceptor interceptor) {
        List<InnerInterceptor> inners = interceptor.getInterceptors();
        return inners.stream()
                .filter(PaginationInnerInterceptor.class::isInstance)
                .map(PaginationInnerInterceptor.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未注册 PaginationInnerInterceptor"));
    }
}
