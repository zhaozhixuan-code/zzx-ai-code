package com.zzx.zzxaicode.core;

import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiCodeGeneratorFacadeTest {

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Test
    void generateAndSaveCode() {
        File file = aiCodeGeneratorFacade.generateAndSaveCode("帮我做一个工作记录的小工具,不超过30行代码", CodeGenTypeEnum.HTML, 1L);
        Assertions.assertNotNull(file);

    }

    @Test
    void generateAndSaveCodeStream() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream("帮我做一个工作记录的小工具,不超过30行代码", CodeGenTypeEnum.MULTI_FILE, 373574253417553920L);
        // 阻塞等待所有数据接收完毕
        List<String> block = codeStream.collectList().block();
        System.out.println(block);
    }

    @Test
    void generateVueProjectCodeStream() {
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                "帮我做一个简单的任务记录网站，总代码量不超过 300 行",
                CodeGenTypeEnum.VUE_PROJECT, 4L);
        // 阻塞等待所有数据收集完成
        List<String> result = codeStream.collectList().block();
        // 验证结果
        System.out.println(result);
        String completeContent = String.join("", result);
        Assertions.assertNotNull(completeContent);
    }

}