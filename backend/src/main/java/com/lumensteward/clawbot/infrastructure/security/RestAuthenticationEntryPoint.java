package com.lumensteward.clawbot.infrastructure.security;

import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证入口点（G-08 / AC-E2）。
 *
 * <p>凡未携带有效 Token 访问受保护接口，一律返回 <b>HTTP 401</b> + 统一响应体
 * （{@code code=20001/20002/20007}），<b>绝不</b>返回 200 或裸露的容器默认页。
 * 具体错误码由 {@link JwtAuthenticationFilter} 写入请求属性后在此读取。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attribute = request.getAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE);
        ErrorCode errorCode = attribute instanceof ErrorCode code ? code : ErrorCode.UNAUTHENTICATED;
        write(response, errorCode);
    }

    private void write(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.getHttpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(JsonUtils.toJson(ApiResponse.error(errorCode)));
    }
}
