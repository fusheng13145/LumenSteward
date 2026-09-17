package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.common.exception.SignatureInvalidException;
import com.lumensteward.clawbot.common.exception.TimestampOutOfWindowException;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifierImpl;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 独立验证：微信验签与时间窗（SRS BR-01 / AC-A1 / AC-A2 / AC-A5）。
 *
 * <p>由 QA 独立编写（不复用工程师测试类）；不依赖 Spring 容器与数据库。
 */
class WechatSignatureVerificationTest {

    private static final String TOKEN = "qa-verify-token";

    private WechatSignatureVerifierImpl verifier() {
        return new WechatSignatureVerifierImpl(new WechatProperties(TOKEN, "", "", "", true, 300, 300));
    }

    private static String nowSeconds() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    @Test
    @DisplayName("AC-A1：正确签名（sha1(sort(token,ts,nonce))）被受理")
    void validSignatureAccepted() {
        String ts = nowSeconds();
        String nonce = "qa-nonce-1";
        String signature = WechatSignatureVerifierImpl.computeSignature(TOKEN, ts, nonce);

        // 不抛异常即受理
        verifier().verify(signature, ts, nonce, null);
    }

    @Test
    @DisplayName("AC-A2：伪造签名连续 100 次必须全部被拒（0 次放行）")
    void forgedSignatureRejected100Times() {
        String ts = nowSeconds();
        int rejected = 0;
        for (int i = 0; i < 100; i++) {
            try {
                verifier().verify("deadbeef", ts, "nonce-" + i, null);
            } catch (SignatureInvalidException e) {
                rejected++;
            }
        }
        assertThat(rejected).as("伪造签名必须 100/100 被拒").isEqualTo(100);
    }

    @Test
    @DisplayName("AC-A2：签名大小写不敏感，但内容不符仍拒")
    void tamperedSignatureRejected() {
        String ts = nowSeconds();
        String nonce = "n";
        String good = WechatSignatureVerifierImpl.computeSignature(TOKEN, ts, nonce);
        // 篡改一位
        String bad = good.substring(0, good.length() - 1)
                + (good.endsWith("0") ? "1" : "0");
        assertThatThrownBy(() -> verifier().verify(bad, ts, nonce, null))
                .isInstanceOf(SignatureInvalidException.class);
    }

    @Test
    @DisplayName("AC-A5：时间戳超窗（now-400s）即使签名正确也必须被拒")
    void outOfWindowRejected() {
        String oldTs = String.valueOf(System.currentTimeMillis() / 1000L - 400L);
        String nonce = "n";
        String signature = WechatSignatureVerifierImpl.computeSignature(TOKEN, oldTs, nonce);

        assertThatThrownBy(() -> verifier().verify(signature, oldTs, nonce, null))
                .isInstanceOf(TimestampOutOfWindowException.class);
    }

    @Test
    @DisplayName("AC-A5：未来超窗（now+400s）同样被拒")
    void futureOutOfWindowRejected() {
        String futureTs = String.valueOf(System.currentTimeMillis() / 1000L + 400L);
        String nonce = "n";
        String signature = WechatSignatureVerifierImpl.computeSignature(TOKEN, futureTs, nonce);

        assertThatThrownBy(() -> verifier().verify(signature, futureTs, nonce, null))
                .isInstanceOf(TimestampOutOfWindowException.class);
    }

    @Test
    @DisplayName("签名参数缺失必须被拒")
    void missingParamsRejected() {
        assertThatThrownBy(() -> verifier().verify(null, nowSeconds(), "n", null))
                .isInstanceOf(SignatureInvalidException.class);
    }
}
