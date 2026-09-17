package com.lumensteward.clawbot.common.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * 全局唯一的分页响应契约（8.2.1 / G-10）。
 *
 * <p>字段固定为 {@code { list, total, page, pageSize }}；后端只在本类定义一次，前端对应
 * {@code src/types/api.ts}，两端不得出现第二套分页字段。
 *
 * @param <T> 记录类型
 */
@Getter
public class PageResult<T> {

    /** 当前页数据。 */
    private final List<T> list;

    /** 总记录数。 */
    private final long total;

    /** 当前页码（从 1 开始）。 */
    private final long page;

    /** 每页条数。 */
    private final long pageSize;

    public PageResult(List<T> list, long total, long page, long pageSize) {
        this.list = list == null ? Collections.emptyList() : list;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long page, long pageSize) {
        return new PageResult<>(list, total, page, pageSize);
    }

    /** 空结果（列表为空但分页信息保留）。 */
    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(Collections.emptyList(), 0L, page, pageSize);
    }

    /** 由 MyBatis-Plus 分页对象转换。 */
    public static <T> PageResult<T> from(IPage<T> mpPage) {
        if (mpPage == null) {
            return new PageResult<>(Collections.emptyList(), 0L, PageQuery.DEFAULT_PAGE,
                    PageQuery.DEFAULT_PAGE_SIZE);
        }
        return new PageResult<>(mpPage.getRecords(), mpPage.getTotal(),
                mpPage.getCurrent(), mpPage.getSize());
    }
}
