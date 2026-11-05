package com.lumensteward.clawbot.support;

import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameStore;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存桌游会话桩（W19 测试支撑；与生产 Redis 实现同语义：覆盖写即换局、删除返回是否存在）。
 */
public final class InMemoryGameStore implements GameStore {

    private final Map<String, GameSession> sessions = new HashMap<>();

    @Override
    public boolean save(String openid, GameSession session) {
        if (openid == null || openid.isBlank() || session == null) {
            return false;
        }
        sessions.put(openid, session);
        return true;
    }

    @Override
    public Optional<GameSession> find(String openid) {
        return Optional.ofNullable(sessions.get(openid));
    }

    @Override
    public boolean delete(String openid) {
        return sessions.remove(openid) != null;
    }
}
