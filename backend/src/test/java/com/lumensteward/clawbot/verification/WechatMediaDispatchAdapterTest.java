package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.domain.port.MediaDispatchException;
import com.lumensteward.clawbot.domain.port.model.MediaReference;
import com.lumensteward.clawbot.infrastructure.client.WechatMediaDispatchAdapter;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatTransport;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.MediaId;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.SendResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WechatMediaDispatchAdapter} 单元测试（FR-11 出站 / SRS）。
 *
 * <p>验证其实现领域端口 {@link com.lumensteward.clawbot.domain.port.MediaDispatchPort}，
 * 隔离微信基础设施细节；上传/下发异常统一转译为 {@link MediaDispatchException}。不反向依赖微信类型。
 */
class WechatMediaDispatchAdapterTest {

    private final WechatTransport transport = mock(WechatTransport.class);
    private final WechatMediaDispatchAdapter adapter = new WechatMediaDispatchAdapter(transport);

    @Test
    @DisplayName("FR-11：上传+下发成功 → 返回领域 MediaReference（不暴露微信 MediaId）")
    void shouldDispatchSuccessfully() throws MediaDispatchException {
        when(transport.uploadTempMedia(any(), eq("voice"), any()))
                .thenReturn(new MediaId("mid-xyz", "voice", null));
        when(transport.sendCustomerMessage(eq("openid-1"), any())).thenReturn(mock(SendResult.class));

        MediaReference ref = adapter.sendVoice("openid-1", "audio-bytes".getBytes(), "v.amr");

        assertThat(ref.mediaId()).isEqualTo("mid-xyz");
        assertThat(ref.mediaType()).isEqualTo("voice");
    }

    @Test
    @DisplayName("FR-11：缺少 openid → MediaDispatchException")
    void shouldRejectBlankOpenid() {
        assertThatThrownBy(() -> adapter.sendVoice("  ", "bytes".getBytes(), "v.amr"))
                .isInstanceOf(MediaDispatchException.class)
                .hasMessageContaining("缺少接收用户");
    }

    @Test
    @DisplayName("FR-11：上传失败 → 转译 MediaDispatchException")
    void shouldTranslateUploadFailure() {
        when(transport.uploadTempMedia(any(), any(), any()))
                .thenThrow(new RuntimeException("上传超时"));

        assertThatThrownBy(() -> adapter.sendVoice("openid-1", "bytes".getBytes(), "v.amr"))
                .isInstanceOf(MediaDispatchException.class);
    }
}
