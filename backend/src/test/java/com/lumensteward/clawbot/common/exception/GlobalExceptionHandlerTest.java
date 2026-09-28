package com.lumensteward.clawbot.common.exception;

import com.lumensteward.clawbot.common.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全局异常处理单测（§7.5 待处理项回归：未匹配路径不得误报 500）。
 */
class GlobalExceptionHandlerTest {

    @Test
    @DisplayName("未匹配路径（NoResourceFoundException）→ 404 / 30005，而非落入 500 兜底")
    void shouldReturn404ForUnmatchedPath() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ApiResponse<Object>> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/api/definitely-not-mapped"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(30005);
        assertThat(response.getBody().getMessage()).isEqualTo("资源不存在");
    }
}
