package com.zzx.zzxaicode.config;

import dev.langchain4j.community.store.memory.chat.redis.RedisChatMemoryStore;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 配置类，用于配置 Redis 连接相关参数
 * 该配置类用于 ChatMemoryStore 的 Redis 实现
 */
@Configuration
@ConfigurationProperties(prefix = "spring.data.redis")
@Data
public class RedisChatMemoryStoreConfig {

    /**
     * Redis 服务器主机地址
     */
    private String host;

    /**
     * Redis 服务器端口
     */
    private int port;

    /**
     * Redis 密码
     */
    private String password;

    /**
     * Redis 数据库索引 (默认为0)
     */
    private int database;

    /**
     * key - value 过期时间
     */
    private long ttl;

    /**
     * 创建 RedisChatMemoryStore 实例
     *
     * @return RedisChatMemoryStore 实例
     */
    @Bean
    public RedisChatMemoryStore redisChatMemoryStore() {
        RedisChatMemoryStore redisChatMemoryStore = RedisChatMemoryStore.builder().
                host(host).
                port(port).
                password(password).
                ttl(ttl).
                build();
        return redisChatMemoryStore;
    }
}
