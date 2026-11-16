package com.zzx.zzxaicode.core;

import com.zzx.zzxaicode.ai.common.AiCodeGenerator;
import com.zzx.zzxaicode.constants.AppConstant;
import com.zzx.zzxaicode.core.builder.VueProjectBuilder;
import com.zzx.zzxaicode.core.parser.GeneratedCodeResultParser;
import com.zzx.zzxaicode.core.saver.CodeFileSaverExecutor;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.File;

/** 生成门面保留解析、落盘与构建边界，框架协议由生成适配处理。 */
@Service
public class AiCodeGeneratorFacade {
    private final AiCodeGenerator generator;
    private final VueProjectBuilder vueProjectBuilder;

    /**
     * 注入项目生成契约与现有 Vue 构建器。
     * @param generator 主业务生成适配
     * @param vueProjectBuilder Vue 工程构建能力
     */
    public AiCodeGeneratorFacade(AiCodeGenerator generator, VueProjectBuilder vueProjectBuilder) {
        this.generator = generator;
        this.vueProjectBuilder = vueProjectBuilder;
    }

    /**
     * 同步生成普通类型并按现有规则解析、保存。
     * @param userMessage 本轮用户需求
     * @param codeGenType HTML 或多文件类型
     * @param appId 服务端应用 ID
     * @return 保存后的应用目录
     * @throws BusinessException 类型为空、Vue 类型使用同步入口、应用 ID 为空或生成结果无效
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum codeGenType, Long appId) {
        validateType(codeGenType);
        String code = generator.generate(appId, userMessage, codeGenType);
        return CodeFileSaverExecutor.executeSaver(GeneratedCodeResultParser.parse(code, codeGenType), codeGenType, appId);
    }

    /**
     * 普通模式结束后解析保存；Vue 工具结束后同步等待构建成功再完成响应。
     * @param userMessage 本轮用户需求
     * @param codeGenType 生成类型
     * @param appId 服务端应用 ID
     * @return 保持现有上游契约的文本或 JSON 事件流
     * @throws BusinessException 类型为空；订阅后的模型、解析、保存或构建错误通过流传播
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenType, Long appId) {
        validateType(codeGenType);
        // 组装时捕获服务层的监控标签；每次订阅的生成状态仍由生成适配器独立创建。
        Flux<String> stream = generator.stream(appId, userMessage, codeGenType);
        return Flux.defer(() -> {
            StringBuilder content = new StringBuilder();
            if (codeGenType == CodeGenTypeEnum.VUE_PROJECT) {
                // Vue 源文件已由工具逐步写入；必须等最终续答结束并构建成功，才能完成上游流。
                return stream.concatWith(Mono.fromRunnable(() -> {
                    String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + "vue_project_" + appId;
                    if (!vueProjectBuilder.buildProject(projectPath)) {
                        throw new BusinessException(ErrorCode.OPERATION_ERROR, "Vue 项目构建失败，无法预览");
                    }
                }).subscribeOn(Schedulers.boundedElastic()).thenMany(Flux.empty()));
            }
            // 页面收到的是实时片段，解析器需要完整结果；保存必须在流完成前执行，失败向外传播。
            return stream.doOnNext(content::append).concatWith(Mono.fromRunnable(() -> {
                Object parsed = GeneratedCodeResultParser.parse(content.toString(), codeGenType);
                CodeFileSaverExecutor.executeSaver(parsed, codeGenType, appId);
            }).subscribeOn(Schedulers.boundedElastic()).thenMany(Flux.empty()));
        });
    }

    /**
     * 防止空类型进入模型请求。
     * @param type 应用保存的生成类型
     * @throws BusinessException 类型为空
     */
    private void validateType(CodeGenTypeEnum type) {
        if (type == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
    }
}
