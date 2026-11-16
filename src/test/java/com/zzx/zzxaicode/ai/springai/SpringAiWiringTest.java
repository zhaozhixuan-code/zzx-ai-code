package com.zzx.zzxaicode.ai.springai;

import com.zzx.zzxaicode.ai.common.AiCodeGenerator;
import com.zzx.zzxaicode.ai.common.CodeGenTypeRouter;
import com.zzx.zzxaicode.ai.common.ProjectFileOperations;
import com.zzx.zzxaicode.config.springai.OpenAiConnectionProperties;
import com.zzx.zzxaicode.config.springai.SpringAiModelConfig;
import com.zzx.zzxaicode.config.springai.SpringAiProperties;
import com.zzx.zzxaicode.core.AiCodeGeneratorFacade;
import com.zzx.zzxaicode.core.builder.VueProjectBuilder;
import com.zzx.zzxaicode.core.handler.JsonMessageStreamHandler;
import com.zzx.zzxaicode.monitor.AiModelMetricsCollector;
import com.zzx.zzxaicode.service.ChatHistoryService;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.ai.model.openai.autoconfigure.*;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 不启动数据库/Redis 或 HTTP 监听，验证真实 YAML、Bean 命名与主业务注入。 */
class SpringAiWiringTest {
    /** 原 LangChain 模型 Bean 与三个新角色共存，自动模型禁用且属性覆盖可生效。 */
    @Test
    void applicationYamlWiresNewContractsWithoutOldModelBeanCollision() {
        new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
                            context.getEnvironment().getPropertySources().addLast(source);
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withPropertyValues("AI_BASE_URL=http://127.0.0.1:1", "AI_API_KEY=fake-key", "AI_MODEL=fake-model",
                        "app.ai.routing.model=router-model", "app.ai.vue.completions-path=/custom/completions")
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
                        RestClientAutoConfiguration.class, WebClientAutoConfiguration.class,
                        OpenAiChatAutoConfiguration.class, OpenAiEmbeddingAutoConfiguration.class, OpenAiImageAutoConfiguration.class,
                        OpenAiAudioSpeechAutoConfiguration.class, OpenAiAudioTranscriptionAutoConfiguration.class, OpenAiModerationAutoConfiguration.class))
                .withBean("openAiChatModel", dev.langchain4j.model.chat.ChatModel.class,
                        () -> mock(dev.langchain4j.model.chat.ChatModel.class))
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withBean(ChatHistoryService.class, () -> mock(ChatHistoryService.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(AiModelMetricsCollector.class, () -> mock(AiModelMetricsCollector.class))
                .withBean(VueProjectBuilder.class, () -> mock(VueProjectBuilder.class))
                .withUserConfiguration(SpringAiModelConfig.class, SpringAiPromptLoader.class, ProjectFileOperations.class,
                        SpringAiConversationRepository.class, SpringAiToolCallbacks.class, SpringAiCodeGenerator.class,
                        SpringAiCodeGenTypeRouter.class, SpringAiModelObservationHandler.class, AiCodeGeneratorFacade.class,
                        JsonMessageStreamHandler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiCodeGenerator.class).hasSingleBean(CodeGenTypeRouter.class);
                    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(3);
                    assertThat(context.getBean("openAiChatModel")).isInstanceOf(dev.langchain4j.model.chat.ChatModel.class);
                    assertThat(context.getBean(OpenAiConnectionProperties.class).getBaseUrl()).isEqualTo("http://127.0.0.1:1");
                    assertThat(context.getBean(SpringAiProperties.class).getRouting().getModel()).isEqualTo("router-model");
                    assertThat(context.getBean(SpringAiProperties.class).getVue().getCompletionsPath()).isEqualTo("/custom/completions");
                });
    }
}
