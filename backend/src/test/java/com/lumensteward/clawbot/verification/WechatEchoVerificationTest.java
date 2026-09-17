package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.common.exception.SignatureInvalidException;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifier;
import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * 独立验证：GET 服务器配置校验原样回显 {@code echostr}（SRS FR-01 步骤⑧ / EI-01 / AC-A4）。
 *
 * <p>无 DB 依赖；以真实控制器 + 验签器替身验证"验签通过才回显、内容原样返回"。
 */
class WechatEchoVerificationTest {

    private WechatCallbackController controller(WechatSignatureVerifier verifier) {
        return new WechatCallbackController(verifier, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("AC-A4：验签通过后 echostr 原样返回（无引号、无包装）")
    void echostrEchoedVerbatim() {
        WechatSignatureVerifier verifier = mock(WechatSignatureVerifier.class);
        doNothing().when(verifier).verify(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());

        String echo = controller(verifier).verify("sig", "1", "n", "abc123");

        assertThat(echo).isEqualTo("abc123");
    }

    @Test
    @DisplayName("AC-A4/BR-01：验签失败时不得回显（异常上抛，交由全局异常处理为失败响应）")
    void echostrNotReturnedWhenSignatureInvalid() {
        WechatSignatureVerifier verifier = mock(WechatSignatureVerifier.class);
        doThrow(new SignatureInvalidException("bad"))
                .when(verifier).verify(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> controller(verifier).verify("bad", "1", "n", "abc123"))
                .isInstanceOf(SignatureInvalidException.class);
    }
}
