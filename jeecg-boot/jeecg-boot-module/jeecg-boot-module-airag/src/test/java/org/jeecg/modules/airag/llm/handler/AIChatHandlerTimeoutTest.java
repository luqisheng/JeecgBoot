package org.jeecg.modules.airag.llm.handler;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.jeecg.ai.handler.LLMHandler;
import org.jeecg.config.AiChatConfig;
import org.jeecg.config.AiRagConfigBean;
import org.jeecg.modules.airag.common.consts.AiragConsts;
import org.jeecg.modules.airag.common.handler.AIChatParams;
import org.jeecg.modules.airag.llm.entity.AiragModel;
import org.jeecg.modules.airag.llm.mapper.AiragMcpMapper;
import org.jeecg.modules.airag.llm.mapper.AiragModelMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 聊天超时规范化单测：
 * 默认300秒、下限30秒、上限1800秒、应用参数优先于模型参数。
 *
 * @author claude
 * @since 2026-11-05 【AI】SSE 容错加固
 */
@ExtendWith(MockitoExtension.class)
class AIChatHandlerTimeoutTest {

    private static final String MODEL_ID = "model-1";

    @Mock
    private AiragModelMapper airagModelMapper;
    @Mock
    private AiragMcpMapper airagMcpMapper;
    @Mock
    private EmbeddingHandler embeddingHandler;
    @Mock
    private LLMHandler llmHandler;
    @Mock
    private AiRagConfigBean aiRagConfigBean;
    @Mock
    private AiChatConfig aiChatConfig;

    @InjectMocks
    private AIChatHandler aiChatHandler;

    @Test
    void emptyTimeoutShouldFallbackToDefault300Seconds() {
        assertEquals(AiragConsts.DEFAULT_TIMEOUT, captureTimeout(null, null));
    }

    @Test
    void tooSmallTimeoutShouldBeRaisedTo30Seconds() {
        AIChatParams params = new AIChatParams();
        params.setTimeout(10);
        assertEquals(30, captureTimeout(params, null).intValue());
    }

    @Test
    void tooLargeTimeoutShouldBeCappedAt1800Seconds() {
        AIChatParams params = new AIChatParams();
        params.setTimeout(99999);
        assertEquals(1800, captureTimeout(params, null).intValue());
    }

    @Test
    void modelParamsTimeoutShouldApplyWhenParamsEmpty() {
        assertEquals(60, captureTimeout(null, "{\"timeout\":60}").intValue());
    }

    @Test
    void appParamsTimeoutShouldWinOverModelParams() {
        AIChatParams params = new AIChatParams();
        params.setTimeout(120);
        assertEquals(120, captureTimeout(params, "{\"timeout\":60}").intValue());
    }

    private Integer captureTimeout(AIChatParams params, String modelParams) {
        when(airagModelMapper.getByIdIgnoreTenant(MODEL_ID)).thenReturn(buildModel(modelParams));
        List<ChatMessage> messages = List.of(new UserMessage("hi"));
        aiChatHandler.chat(MODEL_ID, messages, params);
        ArgumentCaptor<AIChatParams> captor = ArgumentCaptor.forClass(AIChatParams.class);
        verify(llmHandler).chat(anyList(), captor.capture());
        return captor.getValue().getTimeout();
    }

    private AiragModel buildModel(String modelParams) {
        AiragModel model = new AiragModel();
        model.setId(MODEL_ID);
        model.setProvider("OPENAI");
        model.setModelName("gpt-test");
        model.setBaseUrl("http://127.0.0.1:1/v1");
        model.setCredential("{\"apiKey\":\"test-key\"}");
        model.setModelParams(modelParams);
        model.setActivateFlag(1);
        return model;
    }
}
