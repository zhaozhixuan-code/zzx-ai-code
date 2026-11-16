package com.zzx.zzxaicode.ai.langchain;

import com.zzx.zzxaicode.ai.langchain.model.HtmlCodeResult;
import com.zzx.zzxaicode.ai.langchain.model.MultiFileCodeResult;
import com.zzx.zzxaicode.ai.langchain.AiCodeGeneratorService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AiCodeGeneratorServiceTest {

    @Resource
    private AiCodeGeneratorService aiCodeGeneratorService;

    @Test
    void generateHtmlCode() {
        String result = aiCodeGeneratorService.generateHtmlCode("帮我做一个工作记录的小工具");
        System.out.println(result);
    }

    @Test
    void generateMultiFileCode() {
        String result = aiCodeGeneratorService.generateMultiFileCode("请帮我生成一个留言板");
        System.out.println(result);
    }

    @Test
    void generateHtmlCodeForResult() {
        HtmlCodeResult htmlCodeResult = aiCodeGeneratorService.generateHtmlCodeForResult("帮我做一个工作记录的小工具,不超过30行代码");
        Assertions.assertNotNull(htmlCodeResult);
    }

    @Test
    void generateMultiFileCodeForResult() {
        MultiFileCodeResult multiFileCodeResult = aiCodeGeneratorService.generateMultiFileCodeForResult("请帮我生成一个留言板");
        Assertions.assertNotNull(multiFileCodeResult);
    }
}