package com.zzx.zzxaicode;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 排除用于 RAG Embedding 模块
@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.zzx.zzxaicode.mapper")
public class ZzxAiCodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZzxAiCodeApplication.class, args);
    }

}
