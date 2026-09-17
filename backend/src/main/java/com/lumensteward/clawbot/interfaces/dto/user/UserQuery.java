package com.lumensteward.clawbot.interfaces.dto.user;

import com.lumensteward.clawbot.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 用户列表查询条件（架构 4.3 / GET /api/users）。
 *
 * <p>继承全局唯一分页契约 {@link PageQuery}（G-10），仅追加域内过滤字段。
 */
@Getter
@Setter
public class UserQuery extends PageQuery {

    /** 关键字（匹配 openid / nickname，模糊） */
    private String keyword;

    /** 状态过滤：1-正常 0-禁用（可空） */
    private Integer status;

    /** 最后交互时间下界（可空，ISO 8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 最后交互时间上界（可空，ISO 8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
