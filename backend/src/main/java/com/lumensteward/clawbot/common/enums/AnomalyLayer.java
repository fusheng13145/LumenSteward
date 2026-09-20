package com.lumensteward.clawbot.common.enums;

/**
 * 四层异常分类（对齐 SRS 2.3.5「四层异常分类与判定判据」）。
 *
 * <p>枚举名即落库值（{@code log_anomaly_event.layer}），层次归属由埋点处<b>显式</b>给定，
 * 不经错误码前缀推断。
 */
public enum AnomalyLayer {

    /** L1 接入层：签名校验失败、时间戳越界、消息重复、非法/未知消息类型。 */
    L1,

    /** L2 认知层：LLM 超时、输出格式非法、服务不可用、意图置信度不足。 */
    L2,

    /** L3 工具层：工具未注册、参数缺失、工具超时、上游 5xx、业务空结果。 */
    L3,

    /** L4 输出层：执行性幻觉、内容安全命中、发送失败。 */
    L4
}
