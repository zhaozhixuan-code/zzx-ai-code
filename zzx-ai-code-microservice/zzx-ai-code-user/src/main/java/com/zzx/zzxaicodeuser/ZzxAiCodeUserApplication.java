package com.zzx.zzxaicodeuser;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@MapperScan("com.zzx.zzxaicodeuser.mapper")
@ComponentScan("com.zzx")
public class ZzxAiCodeUserApplication {
    public static void main(String[] args) {
        SpringApplication.run(ZzxAiCodeUserApplication.class, args);
    }
}
