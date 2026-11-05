package com.lumensteward.clawbot.domain.game;

import java.util.Optional;

/**
 * 桌游会话存储端口（迭代 4 W19；domain 层定义、infrastructure 实现，G-14 依赖倒置）。
 *
 * <p><b>fail-open 契约（对齐 {@code RecentImageStore} / {@code TaskStore}）：</b>存储不可用
 * 时 {@link #find} 返回空、{@link #save} 与 {@link #delete} 返回 {@code false}，不抛异常——
 * 上游工具按「无进行中局」处理（游戏工具本轮不可见 / 大局开局失败如实提示），主对话链路不受影响。
 *
 * <p>会话 TTL 与上下文窗口同口径（24h）：局自动过期即「对局自然结束」，工具侧按
 * 会话缺失如实回应，无需额外清理机制。
 */
public interface GameStore {

    /**
     * 保存或覆盖该用户的进行中局（单用户同时至多一局，覆盖即换局）。
     *
     * @param openid  用户（键隔离，BR-07）
     * @param session 会话快照
     * @return 是否写入成功（fail-open：不可用返回 false）
     */
    boolean save(String openid, GameSession session);

    /**
     * 读取该用户的进行中局。
     *
     * @param openid 用户
     * @return 会话；无局或存储不可用返回 empty（fail-open）
     */
    Optional<GameSession> find(String openid);

    /**
     * 结束（删除）该用户的进行中局。
     *
     * @param openid 用户
     * @return 是否删除成功（无局时返回 false；fail-open：不可用也返回 false）
     */
    boolean delete(String openid);
}
