package com.zzx.zzxaicode;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.zzx.zzxaicode.mapper")
public class ZzxAiCodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZzxAiCodeApplication.class, args);
    }

}
