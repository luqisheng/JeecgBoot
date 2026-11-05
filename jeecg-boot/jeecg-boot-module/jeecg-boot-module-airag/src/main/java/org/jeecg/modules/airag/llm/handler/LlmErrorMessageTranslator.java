package org.jeecg.modules.airag.llm.handler;

import com.alibaba.fastjson.JSONObject;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.airag.common.handler.IAIChatHandler;

import java.util.Map;

/**
 * 大模型错误文案翻译器：把上游异常消息翻译成对用户友好的中文提示。
 *
 * <p>匹配顺序（命中即返回）：</p>
 * <ol>
 *   <li>message 为空 → 默认文案；</li>
 *   <li>忽略大小写匹配 {@link IAIChatHandler#MODEL_ERROR_MAP}；</li>
 *   <li>包含 "timed out"（"timeout" 由 map 覆盖）→ 排队提示；</li>
 *   <li>JSON 错误体：error 为对象优先取其中 message 字段（缺失取整个 error 串）、
 *       error 为字符串直接使用、无 error 键时依次尝试 error_msg、message；</li>
 *   <li>结果截断到 200 字，仍为空则回默认文案。</li>
 * </ol>
 *
 * <p>翻译结果只用于发给用户的文案；完整堆栈仍由调用方 log.error 输出，二者不混用。</p>
 *
 * @author claude
 * @since 2026-11-05 【AI】SSE 容错加固
 */
public final class LlmErrorMessageTranslator {

    /** 默认错误文案 */
    public static final String DEFAULT_ERROR_MESSAGE = "调用大模型接口失败，详情请查看后台日志。";

    /** 超时提示文案（AiragChatServiceImpl.onError 超时分支据此决定交互形态） */
    public static final String TIMEOUT_MESSAGE = "当前用户较多，排队中，请稍后再试！";

    private static final int MAX_LENGTH = 200;

    private LlmErrorMessageTranslator() {
    }

    /**
     * onError 入口：内部取 message 并兜底。
     *
     * @param error 原始异常（可为 null）
     * @return 面向用户的错误文案
     */
    public static String translate(Throwable error) {
        return translateMessage(null == error ? null : error.getMessage());
    }

    /**
     * 纯函数入口，便于直接单测。
     *
     * @param message 上游异常消息（可为 null）
     * @return 面向用户的错误文案
     */
    public static String translateMessage(String message) {
        return translateMessage(message, DEFAULT_ERROR_MESSAGE);
    }

    /**
     * 纯函数入口（可指定兜底文案），未命中任何规则时返回 defaultMessage。
     *
     * @param message        上游异常消息（可为 null）
     * @param defaultMessage 兜底文案
     * @return 面向用户的错误文案
     */
    public static String translateMessage(String message, String defaultMessage) {
        if (oConvertUtils.isEmpty(message)) {
            return defaultMessage;
        }
        String lower = message.toLowerCase();
        // 1.复用内置关键字映射（忽略大小写；上游新增条目自动生效）
        for (Map.Entry<String, String> entry : IAIChatHandler.MODEL_ERROR_MAP.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (oConvertUtils.isNotEmpty(key) && oConvertUtils.isNotEmpty(value) && lower.contains(key.toLowerCase())) {
                return clamp(value, defaultMessage);
            }
        }
        // 2.补齐 langchain4j/OkHttp 实际使用的 "Read timed out" 措辞
        if (lower.contains("timed out")) {
            return TIMEOUT_MESSAGE;
        }
        // 3.尝试按 JSON 解析上游错误体；HTML/纯文本解析失败则跳过
        String parsed = parseJsonErrorMessage(message);
        if (oConvertUtils.isNotEmpty(parsed)) {
            return clamp(parsed, defaultMessage);
        }
        return defaultMessage;
    }

    /**
     * 尝试从 JSON 错误体中提取可读文案，解析失败返回 null。
     */
    private static String parseJsonErrorMessage(String message) {
        if (!message.trim().startsWith("{")) {
            return null;
        }
        JSONObject json;
        try {
            json = JSONObject.parseObject(message);
        } catch (Exception e) {
            return null;
        }
        if (json == null || json.isEmpty()) {
            return null;
        }
        Object errorObj = json.get("error");
        if (errorObj instanceof JSONObject) {
            JSONObject errorJson = (JSONObject) errorObj;
            String errorMessage = errorJson.getString("message");
            return oConvertUtils.isNotEmpty(errorMessage) ? errorMessage : errorJson.toJSONString();
        }
        if (errorObj instanceof String) {
            return (String) errorObj;
        }
        if (errorObj != null) {
            return String.valueOf(errorObj);
        }
        String errorMsg = json.getString("error_msg");
        if (oConvertUtils.isNotEmpty(errorMsg)) {
            return errorMsg;
        }
        return json.getString("message");
    }

    private static String clamp(String text, String defaultMessage) {
        String result = text == null ? "" : text.trim();
        if (result.isEmpty()) {
            return defaultMessage;
        }
        return result.length() > MAX_LENGTH ? result.substring(0, MAX_LENGTH) : result;
    }
}
