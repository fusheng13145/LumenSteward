package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;
import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AnomalyEventEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AnomalyEventMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 四层异常埋点落库服务测试（迭代 4 W1）。
 *
 * <p>覆盖三条纪律：<b>脱敏集中</b>（openid 明文绝不出本方法，BR-21）、<b>detail 截断</b>至列宽
 * 255（超长原因串不得撑爆插入语句）、<b>best-effort</b>（插入异常只记 WARN，不外抛）。
 */
class AnomalyEventServiceImplTest {

    private static final String RAW_OPENID = "openid-ABCDEFGHIJKL";

    private final AnomalyEventMapper mapper = mock(AnomalyEventMapper.class);
    private final AnomalyEventServiceImpl service = new AnomalyEventServiceImpl(mapper);
    private final ArgumentCaptor<AnomalyEventEntity> captor =
            ArgumentCaptor.forClass(AnomalyEventEntity.class);

    @Test
    @DisplayName("落库字段：层次显式、来源/错误码原样、openid 为脱敏形态")
    void shouldRecordWithMaskedOpenid() {
        service.record(new AnomalyNotice(AnomalyLayer.L1, "SIGNATURE_INVALID", "wechat.callback",
                RAW_OPENID, "签名不一致"));

        verify(mapper).insert(captor.capture());
        AnomalyEventEntity entity = captor.getValue();
        assertThat(entity.getLayer()).isEqualTo("L1");
        assertThat(entity.getErrorCode()).isEqualTo("SIGNATURE_INVALID");
        assertThat(entity.getSource()).isEqualTo("wechat.callback");
        assertThat(entity.getDetail()).isEqualTo("签名不一致");
        assertThat(entity.getOpenid()).isEqualTo(MaskUtils.openid(RAW_OPENID));
        assertThat(entity.getOpenid()).doesNotContain(RAW_OPENID);
    }

    @Test
    @DisplayName("openid 缺失/过短时落 null 或整体打码，不回退明文")
    void shouldNotLeakWhenOpenidShortOrAbsent() {
        service.record(new AnomalyNotice(AnomalyLayer.L1, "MSG_DUPLICATED", "wechat.callback", null, null));
        service.record(new AnomalyNotice(AnomalyLayer.L1, "MSG_DUPLICATED", "wechat.callback", "short", null));

        verify(mapper, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues().get(0).getOpenid()).isNull();
        assertThat(captor.getAllValues().get(0).getDetail()).isNull();
        assertThat(captor.getAllValues().get(1).getOpenid()).doesNotContain("short");
    }

    @Test
    @DisplayName("detail 超长截断至 255 字符（与列宽一致）")
    void shouldTruncateOverlongDetail() {
        String longDetail = "x".repeat(AnomalyEventServiceImpl.DETAIL_MAX_LENGTH + 45);
        service.record(new AnomalyNotice(AnomalyLayer.L2, "TIMEOUT", "orchestrator", RAW_OPENID, longDetail));

        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail())
                .hasSize(AnomalyEventServiceImpl.DETAIL_MAX_LENGTH)
                .isEqualTo(longDetail.substring(0, AnomalyEventServiceImpl.DETAIL_MAX_LENGTH));
    }

    @Test
    @DisplayName("恰好 255 字符不截断")
    void shouldKeepDetailAtExactLimit() {
        String exact = "y".repeat(AnomalyEventServiceImpl.DETAIL_MAX_LENGTH);
        service.record(new AnomalyNotice(AnomalyLayer.L2, "UNAVAILABLE", "orchestrator", null, exact));

        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getDetail()).isEqualTo(exact);
    }

    @Test
    @DisplayName("插入异常被吞掉：观测写入不得影响主链路")
    void shouldSwallowInsertFailure() {
        doThrow(new IllegalStateException("db down")).when(mapper).insert(any(AnomalyEventEntity.class));

        assertThatCode(() -> service.record(new AnomalyNotice(
                AnomalyLayer.L1, "SIGNATURE_INVALID", "wechat.callback", RAW_OPENID, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("缺少层次或错误码的空壳通知直接忽略，不产生脏数据")
    void shouldIgnoreIncompleteNotice() {
        service.record(null);
        service.record(AnomalyNotice.of(null, "X", "src"));
        service.record(AnomalyNotice.of(AnomalyLayer.L1, null, "src"));

        verify(mapper, never()).insert(any(AnomalyEventEntity.class));
    }
}
