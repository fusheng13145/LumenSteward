package com.lumensteward.clawbot.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.lumensteward.clawbot.common.util.JsonUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置（G-16 配置类收敛 / 8.3 key 命名规范的载体）。
 *
 * <p>提供 {@code RedisTemplate<String, Object>}：key 使用 String 序列化（保证 {@code conv:{openid}}
 * 等键名可读、可跨语言匹配），value 使用带类型信息的 JSON 序列化，便于存取 ChatMessage 列表与
 * 会话上下文对象。
 *
 * <p>Redis key 命名统一 {@code 业务:实体:标识}（8.3）；命名空间 {@code conv:} 与 {@code wx:token:}
 * 不得混用（9.4.6(1)）。
 */
@Configuration
public class RedisConfig {

    /**
     * 会话上下文与缓存使用的通用 RedisTemplate。
     *
     * @param connectionFactory Lettuce/自动装配的连接工厂
     * @return RedisTemplate
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // 复制共享 ObjectMapper，避免污染全局配置；开启默认类型信息以支持多态值
        ObjectMapper redisMapper = JsonUtils.mapper().copy();
        redisMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(redisMapper);

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
