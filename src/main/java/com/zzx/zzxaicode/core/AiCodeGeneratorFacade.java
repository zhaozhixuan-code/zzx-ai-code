package com.zzx.zzxaicode.core;


import cn.hutool.json.JSONUtil;
import com.zzx.zzxaicode.ai.AiCodeGeneratorService;
import com.zzx.zzxaicode.ai.AiCodeGeneratorServiceFactory;
import com.zzx.zzxaicode.ai.model.HtmlCodeResult;
import com.zzx.zzxaicode.ai.model.MultiFileCodeResult;
import com.zzx.zzxaicode.ai.model.message.AiResponseMessage;
import com.zzx.zzxaicode.ai.model.message.ToolExecutedMessage;
import com.zzx.zzxaicode.ai.model.message.ToolRequestMessage;
import com.zzx.zzxaicode.core.parser.CodeParserExecutor;
import com.zzx.zzxaicode.core.saver.CodeFileSaverExecutor;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.View;
import reactor.core.publisher.Flux;

import java.io.File;

/**
 * Ai 代码门面类，保存代码功能
 */
@Service
@Slf4j
public class AiCodeGeneratorFacade {

    /**
     * Ai 代码生成服务
     */
    @Resource
    private AiCodeGeneratorServiceFactory aiCodeGeneratorServiceFactory;
    @Autowired
    private View error;

    /**
     * 生成代码并保存入口方法
     *
     * @param userMessage 用户消息
     * @param codeGenType 生成类型
     * @return 生成的代码文件
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenType, Long appId) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据 appId 获取 Ai 代码生成服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenType);
        return switch (codeGenType) {
            case HTML -> {
                HtmlCodeResult htmlCodeResult = aiCodeGeneratorService.generateHtmlCodeForResult(userMessage);
                yield CodeFileSaverExecutor.executeSaver(htmlCodeResult, CodeGenTypeEnum.HTML, appId);
            }

            case MULTI_FILE -> {
                MultiFileCodeResult multiFileCodeResult = aiCodeGeneratorService.generateMultiFileCodeForResult(userMessage);
                yield CodeFileSaverExecutor.executeSaver(multiFileCodeResult, CodeGenTypeEnum.MULTI_FILE, appId);
            }

            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的生成类型" + codeGenType.getValue());
        };
    }


    /**
     * 生成代码并保存入口方法 (流式)
     *
     * @param userMessage 用户消息
     * @param codeGenType 生成类型
     * @param appId       应用ID
     * @return 生成的代码文件
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenType, Long appId) {

        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据 appId 获取 Ai 代码生成服务实例
        AiCodeGeneratorService aiCodeGeneratorService = aiCodeGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenType);
        return switch (codeGenType) {
            case HTML: {
                // 调用接口，生成HTML代码
                Flux<String> result = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield processCodeStream(result, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE: {
                // 调用接口，获取多文件代码
                Flux<String> result = aiCodeGeneratorService.generateMultiFileCodeStream(userMessage);
                yield processCodeStream(result, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT: {
                // 调用接口，获取多文件代码
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, userMessage);
                yield processCodeStream(tokenStream);
            }
            default: {
                String errorMessage = "不支持的生成类型" + codeGenType.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMessage);
            }
        };
    }


    /**
     * 通用流式处理(流式)
     *
     * @param codeStream
     * @param codeGenType
     * @param appId       应用ID
     * @return 流式响应
     */
    private Flux<String> processCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenType, Long appId) {
        // 当流式返回生成代码完成后，在保存代码
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
                    // 实时收集代码片段
                    codeBuilder.append(chunk);
                })
                .doOnComplete(() -> {
                    // 流式返回完成后保存代码
                    String completeCode = codeBuilder.toString();
                    // 处理返回的结果字符串
                    Object parsedResult = CodeParserExecutor.executeParser(completeCode, codeGenType);
                    // 保存代码到文件
                    File file = CodeFileSaverExecutor.executeSaver(parsedResult, codeGenType, appId);
                    log.info("代码保存成功，保存路径为：{}", file.getAbsolutePath());
                });
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 对象
     */
    private Flux<String> processCodeStream(TokenStream tokenStream) {
        return Flux.create(
                sink -> {
                    tokenStream
                            // AI 流式响应的内容（文本输出的内容）
                            .onPartialResponse((String partialResponse) -> {
                                AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                                // 往 sink 中发送消息
                                sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                            })
                            // 处理 AI 调用工具时  (第几个工具，工具调用请求)
                            .onPartialToolExecutionRequest((index, partialToolExecutionRequest) -> {
                                ToolRequestMessage toolRequestMessage = new ToolRequestMessage(partialToolExecutionRequest);
                                sink.next(JSONUtil.toJsonStr(toolRequestMessage));
                            })
                            // 工具调用完成的结果
                            .onToolExecuted((ToolExecution toolExecution) -> {
                                ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                                sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                            })
                            // 调用完成，流程结束
                            .onCompleteResponse((ChatResponse chatResponse) -> {
                                sink.complete();
                            })
                            .onError((Throwable error) -> {
                                error.printStackTrace();
                                sink.error(error);
                            })
                            // 开始监听
                            .start();
                }
        );
    }

}
