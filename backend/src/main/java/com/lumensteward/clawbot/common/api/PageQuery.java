package com.lumensteward.clawbot.common.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * 全局唯一的分页请求契约（8.2.1 / G-10）。
 *
 * <p>约定：{@code page ≥ 1}、{@code pageSize} 默认 20、上限 100。控制器只需声明本类型（或其子类），
 * 即可获得一致的参数校验与 {@link PageResult} 出参口径，<b>禁止</b>在各域重复定义第二套分页字段。
 */
@Getter
@Setter
public class PageQuery {

    /** 默认页码。 */
    public static final int DEFAULT_PAGE = 1;

    /** 默认每页条数。 */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数硬上限，防止大页拖垮 DB（8.2.1）。 */
    public static final int MAX_PAGE_SIZE = 100;

    @Min(value = 1, message = "page 必须 ≥ 1")
    private int page = DEFAULT_PAGE;

    @Min(value = 1, message = "pageSize 必须 ≥ 1")
    @Max(value = MAX_PAGE_SIZE, message = "pageSize 不得超过 " + MAX_PAGE_SIZE)
    private int pageSize = DEFAULT_PAGE_SIZE;

    public PageQuery() {
    }

    public PageQuery(int page, int pageSize) {
        this.page = page;
        this.pageSize = pageSize;
    }

    /**
     * 归一化分页参数：越界时回落到合法区间，避免把非法值透传到持久层。
     *
     * @return 归一化后的自身
     */
    public PageQuery normalize() {
        if (page < DEFAULT_PAGE) {
            page = DEFAULT_PAGE;
        }
        if (pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        return this;
    }

    /**
     * 转换为 MyBatis-Plus 分页对象，供持久层使用。
     *
     * @param <T> 记录类型
     * @return MP 分页对象
     */
    public <T> Page<T> toPage() {
        normalize();
        return new Page<>(page, pageSize);
    }
}
