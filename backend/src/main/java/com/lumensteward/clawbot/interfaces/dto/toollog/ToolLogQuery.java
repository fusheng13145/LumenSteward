package com.lumensteward.clawbot.interfaces.dto.toollog;

import com.lumensteward.clawbot.common.api.PageQuery;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 工具调用日志检索条件（架构 4.3 / GET /api/tool-logs）。
 *
 * <p>承接 T03 的 {@code tool_call_log}（同步落库，ADR-003），支持按 traceId/工具名/状态/openid/时间范围检索。
 */
@Getter
@Setter
public class ToolLogQuery extends PageQuery {

    /** 链路标识（精确） */
    private String traceId;

    /** 工具名（精确） */
    private String toolName;

    /** 状态：0-成功 1-失败 2-降级 3-超时 4-未执行（可空） */
    private Integer status;

    /** 用户（精确，查询前不脱敏；出参脱敏） */
    private String openid;

    /** 创建时间下界（可空，ISO 8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;

    /** 创建时间上界（可空，ISO 8601） */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
