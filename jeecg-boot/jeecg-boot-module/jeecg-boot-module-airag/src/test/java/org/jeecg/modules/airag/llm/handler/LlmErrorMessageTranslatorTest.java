package org.jeecg.modules.airag.llm.handler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 大模型错误文案翻译器单测。
 *
 * @author claude
 * @since 2026-11-05 【AI】SSE 容错加固
 */
class LlmErrorMessageTranslatorTest {

    @Test
    void nullMessageShouldFallbackToDefault() {
        assertEquals(LlmErrorMessageTranslator.DEFAULT_ERROR_MESSAGE,
                LlmErrorMessageTranslator.translate(new RuntimeException()));
    }

    @Test
    void htmlGatewayPageShouldFallbackToDefaultWithoutException() {
        String html = "<html><head><title>502 Bad Gateway</title></head>"
                + "<body><p class=\"error-details\">upstream connect error</p></body></html>";
        assertEquals(LlmErrorMessageTranslator.DEFAULT_ERROR_MESSAGE,
                LlmErrorMessageTranslator.translateMessage(html));
    }

    @Test
    void jsonWithoutErrorKeyShouldUseErrorMsg() {
        assertEquals("Access token invalid",
                LlmErrorMessageTranslator.translateMessage("{\"error_code\":110,\"error_msg\":\"Access token invalid\"}"));
    }

    @Test
    void jsonWithErrorObjectShouldMatchModelErrorMap() {
        assertEquals("API账户余额不足，请充值后重试!",
                LlmErrorMessageTranslator.translateMessage("{\"error\":{\"message\":\"Insufficient Balance\"}}"));
    }

    @Test
    void readTimedOutShouldReturnQueueingTip() {
        assertEquals(LlmErrorMessageTranslator.TIMEOUT_MESSAGE,
                LlmErrorMessageTranslator.translateMessage("java.net.SocketTimeoutException: Read timed out"));
    }

    @Test
    void errorMapMatchShouldBeCaseInsensitive() {
        assertEquals("无效的API密钥!",
                LlmErrorMessageTranslator.translateMessage("INVALID_API_KEY"));
    }
}
