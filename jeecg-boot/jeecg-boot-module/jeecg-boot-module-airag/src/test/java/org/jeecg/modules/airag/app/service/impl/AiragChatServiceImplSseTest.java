package org.jeecg.modules.airag.app.service.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;
import org.jeecg.common.system.api.ISysBaseAPI;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.config.AiChatConfig;
import org.jeecg.config.AiRagConfigBean;
import org.jeecg.config.JeecgBaseConfig;
import org.jeecg.modules.airag.app.consts.AiAppConsts;
import org.jeecg.modules.airag.app.controller.AiragChatController;
import org.jeecg.modules.airag.app.entity.AiragApp;
import org.jeecg.modules.airag.app.mapper.AiragAppMapper;
import org.jeecg.modules.airag.app.service.IAiragVariableService;
import org.jeecg.modules.airag.common.consts.AiragConsts;
import org.jeecg.modules.airag.common.handler.AIChatParams;
import org.jeecg.modules.airag.common.handler.IAIChatHandler;
import org.jeecg.modules.airag.common.utils.AiragLocalCache;
import org.jeecg.modules.airag.flow.service.IAiragFlowService;
import org.jeecg.modules.airag.llm.handler.JeecgToolsProvider;
import org.jeecg.modules.airag.llm.handler.LlmErrorMessageTranslator;
import org.jeecg.modules.airag.llm.mapper.AiragModelMapper;
import org.jeecg.modules.airag.llm.service.IAiragFlowPluginService;
import org.jeecg.modules.airag.llm.service.IAiragKnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.CharacterEncodingFilter;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * SSE 会话生命周期与错误协议集成测试：
 * MockMvc standalone + 真实 AiragChatServiceImpl + mock 协作者，
 * 覆盖「错误回调抛异常导致会话永久挂起」的回归护栏。
 *
 * @author claude
 * @since 2026-11-05 【AI】SSE 容错加固
 */
@ExtendWith(MockitoExtension.class)
@Timeout(15)
class AiragChatServiceImplSseTest {

    private static final String APP_ID = "test-app";
    private static final String SHARE_TOKEN = "test-token";
    private static final String MODEL_ID = "test-model";

    @Mock
    private IAIChatHandler aiChatHandler;
    @Mock
    private RedisTemplate redisTemplate;
    @Mock
    private BoundValueOperations boundValueOps;
    @Mock
    private AiragAppMapper airagAppMapper;
    @Mock
    private IAiragFlowService airagFlowService;
    @Mock
    private ISysBaseAPI sysBaseApi;
    @Mock
    private RedisUtil redisUtil;
    @Mock
    private JeecgToolsProvider jeecgToolsProvider;
    @Mock
    private AiragModelMapper airagModelMapper;
    @Mock
    private IAiragFlowPluginService airagFlowPluginService;
    @Mock
    private IAiragKnowledgeService airagKnowledgeService;
    @Mock
    private IAiragVariableService airagVariableService;
    @Mock
    private JeecgBaseConfig jeecgBaseConfig;
    @Mock
    private AiChatConfig aiChatConfig;
    @Mock
    private AiRagConfigBean aiRagConfigBean;
    @Mock
    private ImageGenerationToolBuilder imageGenerationToolBuilder;
    @Mock
    private ImageGenerationContentAssembler imageGenerationContentAssembler;
    @Mock
    private AiragChatRateLimitService rateLimitService;

    @InjectMocks
    private AiragChatServiceImpl chatService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Lombok @RequiredArgsConstructor 触发 Mockito 构造器注入并短路字段注入，故手动补齐以下非 final 字段
        ReflectionTestUtils.setField(chatService, "aiChatHandler", aiChatHandler);
        ReflectionTestUtils.setField(chatService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(chatService, "airagAppMapper", airagAppMapper);
        ReflectionTestUtils.setField(chatService, "airagFlowService", airagFlowService);
        ReflectionTestUtils.setField(chatService, "sysBaseApi", sysBaseApi);
        ReflectionTestUtils.setField(chatService, "redisUtil", redisUtil);
        ReflectionTestUtils.setField(chatService, "jeecgToolsProvider", jeecgToolsProvider);
        ReflectionTestUtils.setField(chatService, "airagModelMapper", airagModelMapper);
        ReflectionTestUtils.setField(chatService, "airagFlowPluginService", airagFlowPluginService);
        ReflectionTestUtils.setField(chatService, "airagKnowledgeService", airagKnowledgeService);
        ReflectionTestUtils.setField(chatService, "airagVariableService", airagVariableService);
        ReflectionTestUtils.setField(chatService, "jeecgBaseConfig", jeecgBaseConfig);
        ReflectionTestUtils.setField(chatService, "aiChatConfig", aiChatConfig);
        ReflectionTestUtils.setField(chatService, "aiRagConfigBean", aiRagConfigBean);
        AiragChatController controller = new AiragChatController();
        ReflectionTestUtils.setField(controller, "chatService", chatService);
        ReflectionTestUtils.setField(controller, "rateLimitService", rateLimitService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new CharacterEncodingFilter(StandardCharsets.UTF_8.name(), true))
                .build();
        when(aiChatHandler.buildUserMessage(anyString(), any())).thenReturn(new UserMessage("hello"));
    }

    @Test
    void errorWithNullMessageShouldSendErrorEventAndCloseStream() throws Exception {
        CapturingTokenStream stream = prepareStream(buildApp(null));
        MvcResult result = performSend();
        // INIT事件已发出、主线程收尾后再触发错误回调,模拟langchain4j的异步回调时序
        stream.emitError(new RuntimeException());

        String body = dispatchBody(result);

        assertTrue(body.contains("\"event\":\"ERROR\""), body);
        assertTrue(body.contains(LlmErrorMessageTranslator.DEFAULT_ERROR_MESSAGE), body);
        assertSseCacheCleaned(extractRequestId(body));
    }

    @Test
    void errorWithHtmlBodyShouldSendErrorEventAndCloseStream() throws Exception {
        CapturingTokenStream stream = prepareStream(buildApp(null));
        MvcResult result = performSend();
        stream.emitError(new RuntimeException(
                "<html><body>502 Bad Gateway<p class=\"error-details\">upstream connect error</p></body></html>"));

        String body = dispatchBody(result);

        assertTrue(body.contains("\"event\":\"ERROR\""), body);
        assertTrue(body.contains(LlmErrorMessageTranslator.DEFAULT_ERROR_MESSAGE), body);
        assertSseCacheCleaned(extractRequestId(body));
    }

    @Test
    void errorWithJsonMissingErrorKeyShouldSendTranslatedMessage() throws Exception {
        CapturingTokenStream stream = prepareStream(buildApp(null));
        MvcResult result = performSend();
        stream.emitError(new RuntimeException("{\"error_code\":110,\"error_msg\":\"Access token invalid\"}"));

        String body = dispatchBody(result);

        assertTrue(body.contains("\"event\":\"ERROR\""), body);
        assertTrue(body.contains("Access token invalid"), body);
        assertSseCacheCleaned(extractRequestId(body));
    }

    @Test
    void normalStopShouldSendMessageEndAndCleanCaches() throws Exception {
        when(redisTemplate.boundValueOps(any())).thenReturn(boundValueOps);
        CapturingTokenStream stream = prepareStream(buildApp(null));
        MvcResult result = performSend();
        stream.emitComplete("hi");

        String body = dispatchBody(result);

        assertTrue(body.contains("\"event\":\"MESSAGE_END\""), body);
        assertSseCacheCleaned(extractRequestId(body));
    }

    private AiragApp buildApp(String metadata) {
        AiragApp app = new AiragApp();
        app.setId(APP_ID);
        app.setName("测试应用");
        app.setStatus(AiAppConsts.STATUS_RELEASE);
        app.setShareToken(SHARE_TOKEN);
        app.setModelId(MODEL_ID);
        app.setMetadata(metadata);
        return app;
    }

    private CapturingTokenStream prepareStream(AiragApp app) {
        when(airagAppMapper.getByIdIgnoreTenant(APP_ID)).thenReturn(app);
        when(imageGenerationToolBuilder.hasImageRelatedPlugin(any())).thenReturn(false);
        when(imageGenerationToolBuilder.hasExplicitImageRequest(any(), any())).thenReturn(false);
        when(imageGenerationToolBuilder.buildTools(any(), any())).thenReturn(Collections.emptyMap());
        CapturingTokenStream stream = new CapturingTokenStream();
        when(aiChatHandler.chat(eq(MODEL_ID), any(), any(AIChatParams.class))).thenReturn(stream);
        return stream;
    }

    @Test
    void appMetadataTimeoutShouldBePassedThrough() throws Exception {
        prepareStream(buildApp("{\"timeout\":120}"));
        performSend();

        ArgumentCaptor<AIChatParams> captor = ArgumentCaptor.forClass(AIChatParams.class);
        verify(aiChatHandler).chat(eq(MODEL_ID), any(), captor.capture());
        assertNotNull(captor.getValue().getTimeout());
        assertEquals(120, captor.getValue().getTimeout().intValue());
    }

    private MvcResult performSend() throws Exception {
        return mockMvc.perform(post("/airag/chat/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"hello\",\"appId\":\"" + APP_ID + "\",\"shareToken\":\"" + SHARE_TOKEN + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private String dispatchBody(MvcResult result) throws Exception {
        return mockMvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
    }

    private String extractRequestId(String body) {
        // SSE响应体可能含多个 data: 事件(如 INIT_REQUEST_ID + ERROR),用正则取第一个requestId即可(uuid无引号)
        Matcher matcher = Pattern.compile("\"requestId\":\"([^\"]+)\"").matcher(body);
        assertTrue(matcher.find(), "requestId应能从SSE响应体中解析: " + body);
        return matcher.group(1);
    }

    private void assertSseCacheCleaned(String requestId) {
        assertNotNull(requestId, "requestId应能从SSE响应体中解析");
        assertNull(AiragLocalCache.get(AiragConsts.CACHE_TYPE_SSE, requestId));
        assertNull(AiragLocalCache.get(AiragConsts.CACHE_TYPE_SSE_SEND_TIME, requestId));
        assertNull(AiragLocalCache.get(AiragConsts.CACHE_TYPE_SSE_HISTORY_MSG, requestId));
    }

    /**
     * 测试用 TokenStream：start() 不自动触发回调，
     * 由测试在 performSend 收尾后显式调用 emitError/emitComplete 触发。
     * 既贴近 langchain4j 异步回调的时序（主请求线程已收尾），
     * 又保证断言过程完全确定、不引入线程竞态。
     */
    private static final class CapturingTokenStream implements TokenStream {

        private Consumer<ChatResponse> completeResponseConsumer;
        private Consumer<Throwable> errorConsumer;

        void emitError(Throwable error) {
            errorConsumer.accept(error);
        }

        void emitComplete(String text) {
            completeResponseConsumer.accept(ChatResponse.builder()
                    .aiMessage(new AiMessage(text))
                    .finishReason(FinishReason.STOP)
                    .build());
        }

        @Override
        public TokenStream onPartialResponse(Consumer<String> consumer) {
            return this;
        }

        @Override
        public TokenStream onRetrieved(Consumer<List<Content>> consumer) {
            return this;
        }

        @Override
        public TokenStream onToolExecuted(Consumer<ToolExecution> consumer) {
            return this;
        }

        @Override
        public TokenStream onPartialThinking(Consumer<PartialThinking> consumer) {
            return this;
        }

        @Override
        public TokenStream onIntermediateResponse(Consumer<ChatResponse> consumer) {
            return this;
        }

        @Override
        public TokenStream beforeToolExecution(Consumer<BeforeToolExecution> consumer) {
            return this;
        }

        @Override
        public TokenStream onCompleteResponse(Consumer<ChatResponse> consumer) {
            this.completeResponseConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream onError(Consumer<Throwable> consumer) {
            this.errorConsumer = consumer;
            return this;
        }

        @Override
        public TokenStream ignoreErrors() {
            return this;
        }

        @Override
        public void start() {
            // 由测试显式触发 emitError/emitComplete，见类注释
        }
    }
}
