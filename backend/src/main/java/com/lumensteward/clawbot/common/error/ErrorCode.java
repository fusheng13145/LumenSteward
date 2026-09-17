package com.lumensteward.clawbot.common.error;

import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 业务错误码（8.5 / 4.4）。
 *
 * <p>编码规则：<b>分段等距、段内切块、段位不重叠不复用</b>；每段预留 999 个码位便于扩展。
 * <ul>
 *   <li>{@code 0}：成功</li>
 *   <li>{@code 10xxx}：参数与校验错误</li>
 *   <li>{@code 20xxx}：认证与授权</li>
 *   <li>{@code 30xxx}：业务规则冲突</li>
 *   <li>{@code 40xxx}：外部依赖异常（不得原样透传微信用户，须经 FR-09 转自然语言）</li>
 *   <li>{@code 50xxx}：系统内部错误</li>
 *   <li>{@code 60xxx}：限流与保护（不得原样透传微信用户）</li>
 *   <li>{@code 70xxx}：链路与工具（70001–70050 工具调用 / 70051–70100 链路编排），
 *       仅用于内部回注模型，HTTP 状态保持 200</li>
 * </ul>
 *
 * <p>每个错误码与 HTTP 状态码<b>显式绑定</b>，禁止一律 500（G-19）；{@code message} 不含
 * 堆栈/SQL/主机名/密钥（G-13），也不得直接复用第三方返回码（G-12）。
 */
@Getter
public enum ErrorCode {

    // ===== 0：成功 =====
    SUCCESS(0, 200, "success"),

    // ===== 10xxx：参数与校验 =====
    PARAM_MISSING(10001, 400, "参数缺失"),
    PARAM_INVALID(10002, 400, "参数格式非法"),
    PAGE_OUT_OF_RANGE(10003, 400, "分页参数越界"),

    // ===== 20xxx：认证与授权 =====
    UNAUTHENTICATED(20001, 401, "未认证"),
    TOKEN_EXPIRED(20002, 401, "登录状态已过期，请重新登录"),
    FORBIDDEN(20003, 403, "权限不足"),
    ACCOUNT_LOCKED(20004, 401, "账号已锁定，请稍后再试"),
    BAD_CREDENTIALS(20005, 401, "账号或密码错误"),
    ACCOUNT_DISABLED(20006, 403, "账号已禁用"),
    TOKEN_REVOKED(20007, 401, "登录状态已失效，请重新登录"),

    // ===== 30xxx：业务规则冲突 =====
    PET_NAME_DUPLICATE(30001, 409, "宠物昵称已存在"),
    PET_BIRTHDAY_INVALID(30002, 400, "生日日期非法"),
    PET_NOT_FOUND(30003, 404, "宠物档案不存在"),
    PET_FIELD_INVALID(30004, 400, "宠物档案字段值域非法"),
    RESOURCE_NOT_FOUND(30005, 404, "资源不存在"),

    // ===== 40xxx：外部依赖 =====
    LLM_TIMEOUT(40001, 503, "模型服务响应超时"),
    LOGISTICS_UNAVAILABLE(40002, 503, "物流服务暂不可用"),
    MAP_QUOTA_EXCEEDED(40003, 503, "地图服务配额超限"),
    TTS_FAILED(40004, 503, "语音合成失败"),
    LLM_UNAVAILABLE(40005, 503, "模型服务暂不可用"),

    // ===== 50xxx：系统内部 =====
    DB_ERROR(50001, 500, "数据服务异常"),
    CACHE_ERROR(50002, 500, "缓存服务异常"),
    SYSTEM_ERROR(50003, 500, "系统内部错误"),

    // ===== 60xxx：限流与保护 =====
    USER_RATE_LIMITED(60001, 429, "请求过于频繁，请稍后再试"),
    BUDGET_EXCEEDED(60002, 429, "已达到当日用量上限"),
    IP_RATE_LIMITED(60003, 429, "请求过于频繁，请稍后再试"),

    // ===== 70xxx：工具调用（70001–70050）=====
    TOOL_NOT_FOUND(70001, 200, "工具未注册"),
    INVALID_ARGS(70002, 200, "工具参数非法"),
    TOOL_FAILED(70003, 200, "工具执行失败"),
    TOOL_TIMEOUT(70004, 200, "工具执行超时"),

    // ===== 70xxx：链路编排（70051–70100）=====
    FORCED_CONVERGENCE(70051, 200, "已达最大推理轮次"),
    HALLUCINATION_INTERCEPTED(70052, 200, "执行一致性校验拦截"),
    CONTENT_BLOCKED(70053, 200, "内容不符合安全规范"),
    SAFETY_UNAVAILABLE(70054, 200, "内容安全服务不可用"),
    LLM_INVALID_OUTPUT(70055, 200, "模型输出格式非法"),
    CONTEXT_UNAVAILABLE(70056, 200, "会话上下文暂不可用");

    private static final Map<Integer, ErrorCode> BY_CODE;

    static {
        Map<Integer, ErrorCode> map = new HashMap<>();
        for (ErrorCode code : values()) {
            map.put(code.code, code);
        }
        BY_CODE = Collections.unmodifiableMap(map);
    }

    /** 业务状态码。 */
    private final int code;

    /** 与业务码显式绑定的 HTTP 状态码（G-19）。 */
    private final int httpStatus;

    /** 默认提示文案（已脱敏）。 */
    private final String message;

    ErrorCode(int code, int httpStatus, String message) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.message = message;
    }

    /**
     * 按业务码查找错误码。
     *
     * @param code 业务码
     * @return 匹配的枚举；未注册时返回 {@link Optional#empty()}
     */
    public static Optional<ErrorCode> findByCode(int code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /**
     * 按业务码查找错误码，未命中时回落到 {@link #SYSTEM_ERROR}。
     *
     * @param code 业务码
     * @return 错误码
     */
    public static ErrorCode of(int code) {
        return BY_CODE.getOrDefault(code, SYSTEM_ERROR);
    }
}
