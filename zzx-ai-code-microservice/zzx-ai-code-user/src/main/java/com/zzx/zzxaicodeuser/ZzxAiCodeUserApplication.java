package com.zzx.zzxaicodeuser;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@MapperScan("com.zzx.zzxaicodeuser.mapper")
@ComponentScan("com.zzx")
@EnableDubbo
public class ZzxAiCodeUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZzxAiCodeUserApplication.class, args);
    }
}
