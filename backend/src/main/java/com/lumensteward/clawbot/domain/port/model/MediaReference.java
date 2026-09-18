package com.lumensteward.clawbot.domain.port.model;

/**
 * 媒体引用（领域端口 DTO）。
 *
 * <p>用于 {@link com.lumensteward.clawbot.domain.port.MediaDispatchPort} 的下发结果，
 * 隔离 {@code infrastructure/client/wechat/model/MediaId}，保持 domain 不反向依赖基础设施。
 *
 * @param mediaId   素材 id（微信平台返回）
 * @param mediaType 素材类型（voice/image 等）
 */
public record MediaReference(String mediaId, String mediaType) {
}
