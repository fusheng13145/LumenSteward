package com.lumensteward.clawbot.infrastructure.client.wechat.model;

/**
 * 素材标识（架构 5.1 / SRS 9.4.6）。
 *
 * @param mediaId       素材 id
 * @param type          素材类型（image/voice/video/thumb）
 * @param expireAtEpoch 平台侧失效时间（秒，可空）
 */
public record MediaId(String mediaId, String type, Long expireAtEpoch) {

    /** 便捷构造：无失效时间。 */
    public static MediaId of(String mediaId, String type) {
        return new MediaId(mediaId, type, null);
    }
}
