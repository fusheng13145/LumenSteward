package com.lumensteward.clawbot.domain.context;

import com.lumensteward.clawbot.domain.model.RecentImage;

import java.util.Optional;

/**
 * 最近识图缓存端口（迭代 4 W11 识图缓存与追问续接）。
 *
 * <p>以 {@code img:{openid}} 为命名空间（生产实现见 {@code infrastructure.cache.RedisRecentImageStore}，
 * TTL 与上下文窗口同口径；重新识别即覆盖 = 换图即换缓存）。编排器每轮按「缓存是否存在」填充
 * {@code ToolVisibilityContext.imageCachePresent}，使「追问图片」工具仅在确有可追问图片时
 * 出现在函数 Schema 里（W10 机制的首个真实消费者）。
 *
 * <p><b>故障语义（对齐 {@link ContextStore} / TaskStore）：</b>存储不可用时 {@link #find}
 * 返回空、{@link #save} 返回 {@code false}——后果只是追问工具本轮不可见、识图不缓存，
 * 主链路照常（fail-open），绝不因缓存故障影响对话。
 */
public interface RecentImageStore {

    /**
     * 覆盖写入某用户的最近识图结论（带 TTL；重复识别即覆盖）。
     *
     * @param openid 用户标识
     * @param image  识图结论
     * @return 写入成功返回 true；存储不可用返回 false
     */
    boolean save(String openid, RecentImage image);

    /**
     * 读取某用户的最近识图结论。
     *
     * @param openid 用户标识
     * @return 命中的识图结论；不存在 / 已过期 / 存储不可用返回 {@link Optional#empty()}
     */
    Optional<RecentImage> find(String openid);
}
