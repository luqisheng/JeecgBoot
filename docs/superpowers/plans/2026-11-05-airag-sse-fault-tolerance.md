# AI 模块 SSE 容错加固实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 AI 聊天主链路在任何异常路径下都向客户端发出结束事件并关闭 SSE、清空三个 SSE 缓存键，恢复超时语义（应用级 300 秒默认、[30,1800] 保护）与错误文案可用性（null message / HTML / 缺键 JSON 都不再丢提示）。

**Architecture:** 新增纯静态无状态错误文案翻译器 `LlmErrorMessageTranslator`；把 SSE 关闭与缓存清理收敛为 `closeSSE` + `cleanupSseCache` 单一出口，并在 `createSSE` 上挂 `onCompletion`/`onTimeout` 兜底与 60 分钟硬超时；超时统一在 `AIChatHandler.mergeParams` 单点规范化；失败路径不再向上抛异常。

**Tech Stack:** Java（编译目标 release 17，本机用 JDK 21 运行 Maven）、Spring MVC `SseEmitter`、langchain4j 1.17.2、fastjson1、JUnit5 + Mockito + MockMvc standalone。

**设计文档:** [docs/superpowers/specs/2026-11-04-airag-sse-fault-tolerance-design.md](../specs/2026-11-04-airag-sse-fault-tolerance-design.md)

---

## 执行环境与命令模板

所有 Maven 命令从 `D:/carsmaga/JeecgBoot/jeecg-boot` 目录执行（全局 JAVA_HOME 仍是 JDK 8，必须临时切换为 21）：

```bash
export JAVA_HOME=C:/java/Java/jdk-21
cd D:/carsmaga/JeecgBoot/jeecg-boot
mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='<测试类名逗号分隔>'
```

注意：

- 根 pom 默认跳过测试，**必须**加 `-DskipTests=false`（已实测：不加则输出 `Tests are skipped.` 且直接 BUILD SUCCESS）。
- 不需要 `-am`：上游模块已 install 到本地仓库，只编译 airag 模块约 12~30 秒。
- Mockito 会打印 `self-attaching to enable the inline-mock-maker` 警告，属正常现象，不影响测试结果。
- 本机 `JAVA_HOME` 变量名是 `JAVA_HOME`（Git Bash 下 `export` 生效于当前命令）。

**提交纪律（重要，来自设计文档 §6）：**

- 工作区当前有 4 个未提交改动（`CommonUtils.java`、`AiragChatServiceImpl.java` 的 uploadImage 注释段、`ImageGenerationToolBuilder.java`、`application-dev.yml`）以及未跟踪的 `start.md`，**保持原样，不 stage、不提交**；不要用 `git add -A` / `git add .`，每个提交只 `git add` 明确列出的文件。
- **AiragChatServiceImpl.java 的暂存口径（Tasks 2/3/4/6 必须遵守）**：该文件里除本任务改动外，还带着用户已有的 uploadImage 本地存储改动（hunk 中含 `sys/common/static` 字符串）。提交时不能直接 `git add` 后整文件提交，必须用下面的方式只暂存本任务 hunk，把用户 hunk 继续留在工作区：

  ```bash
  FILE=jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImpl.java
  git add "$FILE"
  git diff --cached -- "$FILE" > /tmp/airag-full.patch
  # 按 hunk 拆分：ours.patch 丢掉含 sys/common/static 的那个 hunk（用户改动），其余 hunks 全保留
  awk 'BEGIN{h=""} /^@@/{if(h!=""){if(!skip)printf "%s",h}; h=$0 ORS; skip=0; next} {h=h $0 ORS; if(index($0,"sys/common/static")>0)skip=1} END{if(h!=""&&!skip)printf "%s",h}' /tmp/airag-full.patch > /tmp/airag-ours.patch
  git reset -q -- "$FILE"
  git apply --cached /tmp/airag-ours.patch
  git diff --cached -- "$FILE" | grep -c "sys/common/static"   # 必须为 0
  git diff -- "$FILE" | grep -c "sys/common/static"            # 必须大于 0（用户改动仍在工作区，通常为 2）
  ```

  验证通过后再 `git commit`（提交前用 `git diff --cached --stat` 确认只有本任务的暂存内容）。提交后 `git status` 中该文件仍应为 ` M`（用户改动未提交，属预期）。若 `git apply --cached` 失败，停下来报 BLOCKED，严禁退化为整文件 `git add` 提交。
- 测试文件（`*Test.java`）在最后一个提交（Task 7）统一提交，修复提交只包含主代码——这与设计文档的 7 个提交划分一致。
- 提交顺序相对设计文档表格做了调整：把"翻译器"提交提到最前（原第 3 个），因为 SSE 修复和失败路径修复都依赖它；其余提交保持原相对顺序，提交信息文案不变。

**提交与任务映射表：**

| 任务 | 提交信息 |
|---|---|
| Task 1 | `fix(airag): 抽出大模型错误文案翻译器，null与非JSON响应不再丢失错误提示` |
| Task 2 | `fix(airag): SSE错误回调异常导致会话永久挂起，onError全兜底并统一清理缓存` |
| Task 3 | `fix(airag): 修正AI聊天超时单位错误（150000秒），恢复应用级超时配置与上限保护` |
| Task 4 | `fix(airag): genAiWriter与聊天失败路径统一关闭SSE并清理缓存` |
| Task 5 | `fix(airag): 模型未激活或不存在时completions回退默认模型，补齐activateFlag判空` |
| Task 6 | `fix(airag): 图片上传失败不再产生裂图，异步海报不再返回假成功` |
| Task 7 | `test(airag): 补SSE错误路径、超时透传与错误文案翻译单测` |

**行号说明：** 下列行号是编写计划时（HEAD `640f6df9`）的当前值，编辑时以文中给出的**旧代码文本**为锚点，不要只依赖行号。

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/org/jeecg/modules/airag/llm/handler/LlmErrorMessageTranslator.java` | 新建 | 错误文案翻译：关键字映射 / 超时 / JSON 解析 / 截断兜底（纯静态） |
| `src/main/java/org/jeecg/modules/airag/llm/handler/AIChatHandler.java` | 修改 | `translateLlmException` 换用翻译器；`mergeParams` 超时规范化；`completions` 回退默认模型；`imageEdit` activateFlag 判空 |
| `src/main/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImpl.java` | 修改 | SSE 生命周期兜底（closeSSE/createSSE/cleanupSseCache）；onError 翻译器化；删 setTimeout；失败路径关闭 SSE；图片路径零散缺陷 |
| `src/test/java/org/jeecg/modules/airag/llm/handler/LlmErrorMessageTranslatorTest.java` | 新建（Task 7 提交） | 翻译器 6 个纯函数用例 |
| `src/test/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImplSseTest.java` | 新建（Task 7 提交） | MockMvc + 真实 Service 的 SSE 错误路径/正常结束/超时透传用例 |
| `src/test/java/org/jeecg/modules/airag/llm/handler/AIChatHandlerTimeoutTest.java` | 新建（Task 7 提交） | 超时默认值/上下限/优先级用例 |

模块源码路径前缀（后文省略）：`jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/`

---

### Task 0: 环境与基线验证

**Files:** 无改动。

- [ ] **Step 1: 切换 JDK 21 并跑现有测试**

```bash
export JAVA_HOME=C:/java/Java/jdk-21
cd D:/carsmaga/JeecgBoot/jeecg-boot
mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AiragChatControllerTest'
```

Expected: `Tests run: 1, Failures: 0, Errors: 0`，`BUILD SUCCESS`（约 17 秒，已实测）。

- [ ] **Step 2: 确认分支与工作区状态**

```bash
cd D:/carsmaga/JeecgBoot
git branch --show-current
git status --short
```

Expected: 分支为 `fix/airag-fault-tolerance`；工作区 4 个 `M` 文件 + `?? start.md`（与任务开始时一致）。若分支不对或出现其它改动，停下来向用户确认。

---

### Task 1: 错误文案翻译器（提交 1）

**Files:**
- Create: `src/main/java/org/jeecg/modules/airag/llm/handler/LlmErrorMessageTranslator.java`
- Modify: `src/main/java/org/jeecg/modules/airag/llm/handler/AIChatHandler.java:705-726`（`translateLlmException` 第 2 步）
- Test（本任务只创建、不提交）: `src/test/java/org/jeecg/modules/airag/llm/handler/LlmErrorMessageTranslatorTest.java`

- [ ] **Step 1: 先写失败测试**

创建 `LlmErrorMessageTranslatorTest.java`：

```java
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
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='LlmErrorMessageTranslatorTest'`

Expected: **编译错误** `找不到符号: 类 LlmErrorMessageTranslator`（TDD 红灯，符合预期）。

- [ ] **Step 3: 实现翻译器**

创建 `LlmErrorMessageTranslator.java`：

```java
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
```

- [ ] **Step 4: 把 `translateLlmException` 第 2 步换成翻译器**

修改 `AIChatHandler.java`，找到（约 705-726 行）：

```java
            // 2.根据常见异常关键字做细致翻译（大小写不敏感）
            errMsg = matchErrorMsg(exceptionMsg, errMsg);
```

替换为：

```java
            // 2.统一走错误文案翻译器（关键字映射 + JSON解析 + 超时兜底），未命中时保留调用方默认文案
            errMsg = LlmErrorMessageTranslator.translateMessage(exceptionMsg, defaultMsg);
```

其余代码（工具调用消息序列不完整分支、`log.error("AI模型调用异常: {}", errMsg, e)`）保持不变。翻译器与 `AIChatHandler` 同包，无需新增 import。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='LlmErrorMessageTranslatorTest'`

Expected: `Tests run: 6, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 6: 提交（只含主代码）**

```bash
cd D:/carsmaga/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/handler/LlmErrorMessageTranslator.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/handler/AIChatHandler.java
git commit -m "fix(airag): 抽出大模型错误文案翻译器，null与非JSON响应不再丢失错误提示"
```

Expected: 提交成功；`git status` 中 `LlmErrorMessageTranslatorTest.java` 仍是未跟踪文件（Task 7 才提交，属预期）。

---

### Task 2: SSE 生命周期兜底（提交 2）

**Files:**
- Modify: `src/main/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImpl.java`
  - 147 行附近：新增 `SSE_MAX_DURATION_MS` 常量
  - 261-289 行：重写 `closeSSE` + 新增 `cleanupSseCache`
  - 674-687 行：重写 `createSSE` + 新增 `cleanupOwnSseCache`
  - 1779-1824 行：重写 `onError` 回调主体
  - 63 行：删除不再使用的 `import org.jeecg.modules.airag.llm.handler.AIChatHandler;`
- Test（本任务创建、Task 7 提交，先写后改）: `src/test/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImplSseTest.java`

- [ ] **Step 1: 先写失败测试**

创建 `AiragChatServiceImplSseTest.java`（完整文件）：

```java
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
```

- [ ] **Step 2: 跑测试确认失败（这正是"挂起"缺陷的复现）**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AiragChatServiceImplSseTest'`

Expected（修复前）:
- 3 个 `error*` 用例在 `stream.emitError(...)` 处直接失败（Errors 非 0）：回调内 `error.getMessage().contains(key)` 抛 NPE（null message）、`JSONObject.parseObject` 抛 JSONException（HTML）、`get("error").toString()` 抛 NPE（缺键 JSON）——"错误回调自身会抛异常"的根因直接现形（生产环境中该异常由 langchain4j 的回调线程吞掉、无人关流，表现为永久挂起）；
- `normalStopShouldSendMessageEndAndCleanCaches` 失败在 `CACHE_TYPE_SSE_HISTORY_MSG` 未清理。

- [ ] **Step 3: 新增硬超时常量**

在 `AiragChatServiceImpl.java` 中找到（约 147 行）：

```java
    private static final ExecutorService SSE_THREAD_POOL = Executors.newFixedThreadPool(10); // 最大10个线程
```

在其后新增：

```java

    /**
     * SSE 硬超时（60 分钟）：最后一道兜底网。
     * 到点由容器触发 onTimeout，清理缓存并给客户端发一条 ERROR。
     */
    private static final long SSE_MAX_DURATION_MS = 60 * 60 * 1000L;
```

- [ ] **Step 4: 重写 `closeSSE` 并新增 `cleanupSseCache`**

找到（约 261-289 行）整段旧方法：

```java
    private static void closeSSE(SseEmitter emitter, EventData eventData) {
        AssertUtils.assertNotEmpty("请求id不能为空", eventData);
        if (null == emitter) {
            log.warn("会话已关闭");
            return;
        }
        try {
            // 发送完成事件
            emitter.send(SseEmitter.event().data(eventData));
        } catch (Exception e) {
            if(!e.getMessage().contains("ResponseBodyEmitter has already completed")){
                log.error("终止会话时发生错误", e);
            }
            try {
                // 防止异常冒泡
                emitter.completeWithError(e);
            } catch (Exception ignore) {}
        } finally {
            // 从缓存中移除emitter
            AiragLocalCache.remove(AiragConsts.CACHE_TYPE_SSE, eventData.getRequestId());
            // 关闭emitter
            try {
                emitter.complete();
            } catch (Exception ignore) {}
        }
    }
```

替换为：

```java
    private static void closeSSE(SseEmitter emitter, EventData eventData) {
        String requestId = (null != eventData) ? eventData.getRequestId() : null;
        try {
            if (null == emitter) {
                log.warn("会话已关闭,requestId:{}", requestId);
                return;
            }
            try {
                // 发送完成事件
                if (null != eventData) {
                    emitter.send(SseEmitter.event().data(eventData));
                }
            } catch (Exception e) {
                String message = e.getMessage();
                if (message == null || !message.contains("ResponseBodyEmitter has already completed")) {
                    log.error("终止会话时发生错误", e);
                }
                try {
                    // 防止异常冒泡
                    emitter.completeWithError(e);
                } catch (Exception ignore) {}
            }
        } finally {
            // 无论成功失败,统一清理三个缓存键并关闭emitter
            cleanupSseCache(requestId);
            try {
                if (null != emitter) {
                    emitter.complete();
                }
            } catch (Exception ignore) {}
        }
    }

    /**
     * 清理一个会话相关的三个SSE缓存键（幂等，requestId为空时直接跳过）。
     *
     * @param requestId 会话请求id
     */
    private static void cleanupSseCache(String requestId) {
        if (oConvertUtils.isEmpty(requestId)) {
            return;
        }
        AiragLocalCache.remove(AiragConsts.CACHE_TYPE_SSE, requestId);
        AiragLocalCache.remove(AiragConsts.CACHE_TYPE_SSE_SEND_TIME, requestId);
        AiragLocalCache.remove(AiragConsts.CACHE_TYPE_SSE_HISTORY_MSG, requestId);
    }
```

- [ ] **Step 5: 重写 `createSSE` 并新增 `cleanupOwnSseCache`**

找到（约 674-687 行）整段旧方法：

```java
    private static SseEmitter createSSE(String requestId) {
        SseEmitter emitter = new SseEmitter(-0L);
        emitter.onError(throwable -> {
            log.warn("SEE向客户端发送消息失败: {}", throwable.getMessage());
            AiragLocalCache.remove(AiragConsts.CACHE_TYPE_SSE, requestId);
            AiragLocalCache.remove(AiragConsts.CACHE_TYPE_SSE_SEND_TIME, requestId);
            try {
                emitter.complete();
            } catch (Exception ignore) {}
        });
        return emitter;
    }
```

替换为：

```java
    private static SseEmitter createSSE(String requestId) {
        // 60分钟硬上限：到点由容器触发onTimeout，兜底清理并给客户端发送ERROR
        SseEmitter emitter = new SseEmitter(SSE_MAX_DURATION_MS);
        emitter.onError(throwable -> {
            log.warn("SEE向客户端发送消息失败: {}", throwable.getMessage());
            cleanupOwnSseCache(emitter, requestId);
            try {
                emitter.complete();
            } catch (Exception ignore) {}
        });
        emitter.onCompletion(() -> {
            log.info("[AI-CHAT]SSE会话正常结束,requestId:{}", requestId);
            cleanupOwnSseCache(emitter, requestId);
        });
        emitter.onTimeout(() -> {
            log.warn("[AI-CHAT]SSE会话超时已强制结束,requestId:{}", requestId);
            try {
                EventData eventData = new EventData(requestId, null, EventData.EVENT_FLOW_ERROR);
                eventData.setData(EventFlowData.builder().success(false).message("本次会话超时已结束，请重新发起对话。").build());
                emitter.send(SseEmitter.event().data(eventData));
            } catch (Exception e) {
                log.warn("[AI-CHAT]SSE超时通知发送失败,requestId:{}", requestId);
            }
            cleanupOwnSseCache(emitter, requestId);
            try {
                emitter.complete();
            } catch (Exception ignore) {}
        });
        return emitter;
    }

    /**
     * 仅当缓存中登记的SSE还是当前emitter时才清理。
     * 避免"继续接收"(receiveByRequestId)这类旁路emitter结束时误删主会话的缓存。
     */
    private static void cleanupOwnSseCache(SseEmitter emitter, String requestId) {
        if (emitter == null || emitter != AiragLocalCache.get(AiragConsts.CACHE_TYPE_SSE, requestId)) {
            return;
        }
        cleanupSseCache(requestId);
    }
```

- [ ] **Step 6: 重写 `onError` 回调主体**

在 `sendWithDefault`（约 1779-1824 行）找到旧代码片段，从 `log.error(error.getMessage(), error);` 起、到 else 分支的收尾 `}` 为止：

```java
            log.error(error.getMessage(), error);
            String errMsg = error.getMessage();
            if (errMsg != null && errMsg.contains("timeout")) {
                //update-begin---author:chenrui ---date:20250425  for：[QQYUN-12203]AI 聊天，超时或者服务器报错，给个友好提示------------
                errMsg = "当前用户较多，排队中，请稍后再试！";
                EventData eventData = new EventData(requestId, null, EventData.EVENT_MESSAGE, chatConversation.getId(), topicId);
                eventData.setData(EventMessageData.builder().message("\n" + errMsg).build());
                sendMessage2Client(emitter, eventData);
                eventData = new EventData(requestId, null, EventData.EVENT_MESSAGE_END, chatConversation.getId(), topicId);
                closeSSE(emitter, eventData);
                //update-end---author:chenrui ---date:20250425  for：[QQYUN-12203]AI 聊天，超时或者服务器报错，给个友好提示------------
            } else {
                errMsg = "调用大模型接口失败，详情请查看后台日志。";
                boolean isFindErrorMsg = false;
                // 根据常见异常关键字做细致翻译
                for (Map.Entry<String, String> entry : AIChatHandler.MODEL_ERROR_MAP.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (error.getMessage().contains(key)) {
                        errMsg = value;
                        isFindErrorMsg = true;
                    }
                }
                String message = error.getMessage();
                if(!isFindErrorMsg && message.contains("error")) {
                    errMsg = JSONObject.parseObject(message).get("error").toString();
                }
                EventData eventData = new EventData(requestId, null, EventData.EVENT_FLOW_ERROR, chatConversation.getId(), topicId);
                eventData.setData(EventFlowData.builder().success(false).message(errMsg).build());
                closeSSE(emitter, eventData);
            }
```

替换为：

```java
            log.error(error.getMessage(), error);
            // 统一用翻译器兜底：null message、HTML、缺键JSON 都不再抛异常
            String errMsg = LlmErrorMessageTranslator.translate(error);
            if (LlmErrorMessageTranslator.TIMEOUT_MESSAGE.equals(errMsg)) {
                // 超时：沿用原有的"普通消息+正常结束"交互
                EventData eventData = new EventData(requestId, null, EventData.EVENT_MESSAGE, chatConversation.getId(), topicId);
                eventData.setData(EventMessageData.builder().message("\n" + errMsg).build());
                sendMessage2Client(emitter, eventData);
                eventData = new EventData(requestId, null, EventData.EVENT_MESSAGE_END, chatConversation.getId(), topicId);
                closeSSE(emitter, eventData);
            } else {
                EventData eventData = new EventData(requestId, null, EventData.EVENT_FLOW_ERROR, chatConversation.getId(), topicId);
                eventData.setData(EventFlowData.builder().success(false).message(errMsg).build());
                closeSSE(emitter, eventData);
            }
```

- [ ] **Step 7: 新增 import、删除不再使用的 import**

在 `AiragChatServiceImpl.java` 的 import 区（第 60-66 行一带）新增：

```java
import org.jeecg.modules.airag.llm.handler.LlmErrorMessageTranslator;
```

删除（第 63 行，`onError` 重写后已无引用）：

```java
import org.jeecg.modules.airag.llm.handler.AIChatHandler;
```

（确认全文件只有这一处 import 和原先 1806 行的 `AIChatHandler.MODEL_ERROR_MAP` 两处引用；替换后 grep `AIChatHandler` 应只剩 `IAIChatHandler`。）

- [ ] **Step 8: 跑测试确认通过**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AiragChatServiceImplSseTest'`

Expected: `Tests run: 4, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

- [ ] **Step 9: 提交（只含主代码）**

先按开头「提交纪律」中的 **AiragChatServiceImpl.java 暂存口径**完成 hunk 手术（两个 `grep -c` 断言分别必须为 0 和大于 0），然后：

```bash
cd D:/carsmaga/JeecgBoot
git commit -m "fix(airag): SSE错误回调异常导致会话永久挂起，onError全兜底并统一清理缓存"
```

---

### Task 3: 超时修复（提交 3）

**Files:**
- Modify: `src/main/java/org/jeecg/modules/airag/llm/handler/AIChatHandler.java`（新增两个常量；约 356-359 行超时块）
- Modify: `src/main/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImpl.java:1577`（删除 `setTimeout` 覆盖）
- Test: `src/test/java/org/jeecg/modules/airag/llm/handler/AIChatHandlerTimeoutTest.java`（新建，Task 7 提交）
- Test: 在 `AiragChatServiceImplSseTest.java` 追加一个透传用例（本任务追加）

- [ ] **Step 1: 先写失败测试（超时规范化）**

创建 `AIChatHandlerTimeoutTest.java`：

```java
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
```

- [ ] **Step 2: 在 SSE 测试里追加"应用级超时透传"用例**

在 `AiragChatServiceImplSseTest.java` 的 `normalStopShouldSendMessageEndAndCleanCaches` 之后追加：

```java
    @Test
    void appMetadataTimeoutShouldBePassedThrough() throws Exception {
        prepareStream(buildApp("{\"timeout\":120}"));
        performSend();

        ArgumentCaptor<AIChatParams> captor = ArgumentCaptor.forClass(AIChatParams.class);
        verify(aiChatHandler).chat(eq(MODEL_ID), any(), captor.capture());
        assertNotNull(captor.getValue().getTimeout());
        assertEquals(120, captor.getValue().getTimeout().intValue());
    }
```

（此用例不触发任何回调，emitter 保持打开；只做参数捕获断言。）

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AIChatHandlerTimeoutTest,AiragChatServiceImplSseTest'`

Expected:
- `AIChatHandlerTimeoutTest`：`tooSmallTimeoutShouldBeRaisedTo30Seconds` 与 `tooLargeTimeoutShouldBeCappedAt1800Seconds` 失败（当前无上下限）；
- `AiragChatServiceImplSseTest.appMetadataTimeoutShouldBePassedThrough` 失败：实际捕获 150000（`5*30*1000`），不是 120。

- [ ] **Step 4: 新增超时常量**

在 `AIChatHandler.java` 中找到（约 71-73 行）：

```java
    @Autowired
    private AiChatConfig aiChatConfig;
```

在其后新增：

```java

    /** AI调用超时下限（秒） */
    private static final int LLM_TIMEOUT_MIN_SECONDS = 30;
    /** AI调用超时上限（秒） */
    private static final int LLM_TIMEOUT_MAX_SECONDS = 1800;
```

- [ ] **Step 5: `mergeParams` 超时块替换为规范化逻辑**

找到（约 356-359 行）：

```java
        // 默认超时时间
        if(oConvertUtils.isObjectEmpty(params.getTimeout())){
            params.setTimeout(AiragConsts.DEFAULT_TIMEOUT);
        }
```

替换为：

```java
        // 超时统一单位：秒（AiModelFactory 用 Duration.ofSeconds 转换），默认300秒，范围[30,1800]
        Integer timeout = params.getTimeout();
        if (oConvertUtils.isObjectEmpty(timeout)) {
            timeout = AiragConsts.DEFAULT_TIMEOUT;
        } else if (timeout < LLM_TIMEOUT_MIN_SECONDS) {
            log.warn("[AI-CHAT] mergeParams 超时配置过小,已修正为{}秒,原始值={}", LLM_TIMEOUT_MIN_SECONDS, timeout);
            timeout = LLM_TIMEOUT_MIN_SECONDS;
        } else if (timeout > LLM_TIMEOUT_MAX_SECONDS) {
            log.warn("[AI-CHAT] mergeParams 超时配置过大,已修正为{}秒,原始值={}", LLM_TIMEOUT_MAX_SECONDS, timeout);
            timeout = LLM_TIMEOUT_MAX_SECONDS;
        }
        params.setTimeout(timeout);
        log.info("[AI-CHAT] mergeParams 超时生效值={}秒", timeout);
```

说明：该位置位于"应用参数（`sendWithAppChat` 早已设置）+ 模型 `modelParams`（上一段刚填充）"两路来源合并之后，后续代码不再改动 timeout，等价于设计文档要求的"收口在 mergeParams"。

- [ ] **Step 6: 删除服务层的超时覆盖**

在 `AiragChatServiceImpl.java` 中找到（约 1575-1578 行）：

```java
        try {
            aiChatParams.setTimeout(5*30*1000);
            // 打印流程耗时日志
            printChatDuration(requestId, "开始向LLM发送消息");
```

替换为：

```java
        try {
            // 超时统一由 AIChatHandler.mergeParams 规范化（应用metadata > 模型参数 > 默认300秒）
            // 打印流程耗时日志
            printChatDuration(requestId, "开始向LLM发送消息");
```

- [ ] **Step 7: 跑测试确认通过**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AIChatHandlerTimeoutTest,AiragChatServiceImplSseTest'`

Expected: `AIChatHandlerTimeoutTest` 5 个用例全部通过；`AiragChatServiceImplSseTest` 5 个用例（含新增透传用例）全部通过。

- [ ] **Step 8: 提交（只含主代码）**

先按开头「提交纪律」中的 **AiragChatServiceImpl.java 暂存口径**对该文件完成 hunk 手术，再暂存 AIChatHandler.java，最后提交：

```bash
cd D:/carsmaga/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/handler/AIChatHandler.java
git commit -m "fix(airag): 修正AI聊天超时单位错误（150000秒），恢复应用级超时配置与上限保护"
```

---

### Task 4: 失败路径统一关闭 SSE（提交 4）

**Files:**
- Modify: `src/main/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImpl.java`
  - 约 1586-1603 行：`sendWithDefault` 的 `catch` 分支
  - 约 2545-2547 行：`genAiWriter` 的 `sendWithFlow` 调用

本任务无新增自动化用例（设计文档亦未列），以编译 + 既有测试全绿 + Task 7 的完整回归为准。

- [ ] **Step 1: `sendWithDefault` 的 catch 分支：不再 rethrow**

找到（约 1585-1603 行）：

```java
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            // for [QQYUN-9234] MCP服务连接关闭 - 异常时关闭MCP连接
            finalAiChatParams.closeMcpConnections();
            // sse
            SseEmitter emitter = AiragLocalCache.get(AiragConsts.CACHE_TYPE_SSE, requestId);
            if (null == emitter) {
                log.warn("[AI应用]接收LLM返回会话已关闭{}", requestId);
                return;
            }
            String errMsg = "调用大模型接口失败，详情请查看后台日志。";
            if(e instanceof JeecgBootException || e instanceof JeecgBootBizTipException){
                errMsg = e.getMessage();
            }
            EventData eventData = new EventData(requestId, null, EventData.EVENT_FLOW_ERROR, chatConversation.getId(), topicId);
            eventData.setData(EventFlowData.builder().success(false).message(errMsg).build());
            closeSSE(emitter, eventData);
            throw new JeecgBootBizTipException("调用大模型接口失败:" + e.getMessage());
        }
```

替换为：

```java
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            // for [QQYUN-9234] MCP服务连接关闭 - 异常时关闭MCP连接
            finalAiChatParams.closeMcpConnections();
            // sse
            SseEmitter emitter = AiragLocalCache.get(AiragConsts.CACHE_TYPE_SSE, requestId);
            if (null == emitter) {
                log.warn("[AI应用]接收LLM返回会话已关闭{}", requestId);
                return;
            }
            // 业务提示类异常保留原始文案,其余交给翻译器兜底
            String errMsg = (e instanceof JeecgBootException || e instanceof JeecgBootBizTipException)
                    ? e.getMessage()
                    : LlmErrorMessageTranslator.translate(e);
            EventData eventData = new EventData(requestId, null, EventData.EVENT_FLOW_ERROR, chatConversation.getId(), topicId);
            eventData.setData(EventFlowData.builder().success(false).message(errMsg).build());
            closeSSE(emitter, eventData);
            // 错误事件已发给客户端且流已关闭,不再向上抛出,交由doChat最外层兜底
        }
```

说明：设计文档要求"文案改用翻译器"，此处对 `JeecgBootException/JeecgBootBizTipException` 保留原始文案，避免把"当前系统未配置默认图像模型…"这类有信息量的业务提示替换成通用文案；翻译器覆盖其余全部异常（JSON/HTML/null）。

- [ ] **Step 2: `genAiWriter` 包住 `sendWithFlow`**

找到 `genAiWriter` 末尾（约 2545-2547 行）：

```java
        sendWithFlow(requestId, AiAppConsts.ARTICLE_WRITER_FLOW_ID, chatConversation, topicId, new ArrayList<>(), sendParams);
        return emitter;
    }
```

替换为：

```java
        try {
            sendWithFlow(requestId, AiAppConsts.ARTICLE_WRITER_FLOW_ID, chatConversation, topicId, new ArrayList<>(), sendParams);
        } catch (Throwable e) {
            // runFlow同步抛异常时必须关闭SSE并清理缓存,不能让异常冒到Spring变成500 JSON
            log.error("[ai-writer]流程执行失败,requestId:{}", requestId, e);
            EventData errorEventData = new EventData(requestId, null, EventData.EVENT_FLOW_ERROR, conversationId, topicId);
            errorEventData.setData(EventFlowData.builder().success(false).message(LlmErrorMessageTranslator.translate(e)).build());
            closeSSE(emitter, errorEventData);
        }
        return emitter;
    }
```

- [ ] **Step 3: 编译并回归既有用例**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AiragChatServiceImplSseTest,AIChatHandlerTimeoutTest,LlmErrorMessageTranslatorTest,AiragChatControllerTest'`

Expected: 全部通过（17 个用例：翻译器 6 + SSE 5（含透传）+ 超时 5 + 控制器 1；Failures/Errors 必须为 0）。

- [ ] **Step 4: 提交**

先按开头「提交纪律」中的 **AiragChatServiceImpl.java 暂存口径**完成 hunk 手术，然后：

```bash
cd D:/carsmaga/JeecgBoot
git commit -m "fix(airag): genAiWriter与聊天失败路径统一关闭SSE并清理缓存"
```

---

### Task 5: 零散缺陷——completions 回退与 activateFlag 判空（提交 5）

**Files:**
- Modify: `src/main/java/org/jeecg/modules/airag/llm/handler/AIChatHandler.java`
  - 约 102-108 行：`completions(String, List, AIChatParams)`
  - 约 622 行：`imageEdit(AiragModel, ...)`

- [ ] **Step 1: `completions` 回退默认模型**

找到（约 101-108 行）：

```java
    @Override
    public String completions(String modelId, List<ChatMessage> messages, AIChatParams params) {
        AssertUtils.assertNotEmpty("至少发送一条消息", messages);
        AssertUtils.assertNotEmpty("请选择模型", modelId);

        AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
        //AssertUtils.assertSame("模型未激活,请先在[AI模型配置]中[测试激活]模型", airagModel.getActivateFlag(), 1);
        return completions(airagModel, messages, params);
    }
```

替换为：

```java
    @Override
    public String completions(String modelId, List<ChatMessage> messages, AIChatParams params) {
        AssertUtils.assertNotEmpty("至少发送一条消息", messages);
        AssertUtils.assertNotEmpty("请选择模型", modelId);

        AiragModel airagModel = airagModelMapper.getByIdIgnoreTenant(modelId);
        // 与chat()对齐：模型不存在或未激活时回退默认模型
        if (null == airagModel || airagModel.getActivateFlag() == null || airagModel.getActivateFlag() == 0) {
            log.warn("模型未激活或不存在,采用默认模型: modelId={}", modelId);
            return completionsByDefaultModel(messages, params);
        }
        return completions(airagModel, messages, params);
    }
```

- [ ] **Step 2: `imageEdit` 的 activateFlag 判空**

找到（约 622 行，`imageEdit(AiragModel airagModel, String messages, ...)` 方法内）：

```java
        if(null == airagModel || airagModel.getActivateFlag() == 0){
```

替换为（与 `imageGenerate` 同口径）：

```java
        if(null == airagModel || (airagModel.getActivateFlag() != null && airagModel.getActivateFlag() == 0)){
```

- [ ] **Step 3: 编译并回归**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AIChatHandlerTimeoutTest,AiragChatServiceImplSseTest,LlmErrorMessageTranslatorTest'`

Expected: Failures/Errors 为 0。

- [ ] **Step 4: 提交**

```bash
cd D:/carsmaga/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/main/java/org/jeecg/modules/airag/llm/handler/AIChatHandler.java
git commit -m "fix(airag): 模型未激活或不存在时completions回退默认模型，补齐activateFlag判空"
```

---

### Task 6: 图片路径缺陷（提交 6）

**Files:**
- Modify: `src/main/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImpl.java`
  - 约 1149-1154 行：`genImageChat` 的 markdown 组装
  - 约 2246 行：`genAiPoster` 的返回

`uploadImage` 保持"失败返回空串"契约不变；`ImageGenerationToolBuilder` 不改。

- [ ] **Step 1: `genImageChat` 过滤空 URL、全空时发 ERROR**

找到（约 1149-1155 行）：

```java
            // 记录历史消息
            String imageMarkdown = imageList.stream().map(map -> {
                String newUrl = this.uploadImage(map);
                return "![](" + newUrl + ")";
            }).collect(Collectors.joining("\n"));
            //update-end---author:wangshuai---date:2026-01-26---for:【QQYUN-14615】应用门户加入新工具：取绘画id---
```

替换为：

```java
            // 记录历史消息（过滤上传失败的图片，避免生成 ![]() 裂图）
            String imageMarkdown = imageList.stream()
                    .map(this::uploadImage)
                    .filter(url -> oConvertUtils.isNotEmpty(url))
                    .map(url -> "![](" + url + ")")
                    .collect(Collectors.joining("\n"));
            if (oConvertUtils.isEmpty(imageMarkdown)) {
                // 全部上传失败:不追加空的AiMessage,直接给用户可操作的错误
                EventData errorEventData = new EventData(requestId, null, EventData.EVENT_FLOW_ERROR, chatConversation.getId(), topicId);
                errorEventData.setData(EventMessageData.builder().message("图片生成失败：图片上传失败，请检查存储配置。").build());
                errorEventData.setRequestId(requestId);
                closeSSE(emitter, errorEventData);
                return emitter;
            }
            //update-end---author:wangshuai---date:2026-01-26---for:【QQYUN-14615】应用门户加入新工具：取绘画id---
```

- [ ] **Step 2: `genAiPoster` 全空时抛异常**

找到（约 2246 行，`genAiPoster` 方法末尾）：

```java
        return imageList.stream().map(this::uploadImage).collect(Collectors.joining("\n"));
```

替换为：

```java
        String result = imageList.stream()
                .map(this::uploadImage)
                .filter(url -> oConvertUtils.isNotEmpty(url))
                .collect(Collectors.joining("\n"));
        if (oConvertUtils.isEmpty(result)) {
            // 上传全部失败时抛出异常，让异步任务（genAiPosterAsync）落到failed，避免前端轮询到"假成功"
            throw new JeecgBootBizTipException("图片生成失败：图片上传失败，请检查存储配置。");
        }
        return result;
```

- [ ] **Step 3: 编译并回归**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AIChatHandlerTimeoutTest,AiragChatServiceImplSseTest,LlmErrorMessageTranslatorTest'`

Expected: Failures/Errors 为 0。

- [ ] **Step 4: 提交**

先按开头「提交纪律」中的 **AiragChatServiceImpl.java 暂存口径**完成 hunk 手术，然后：

```bash
cd D:/carsmaga/JeecgBoot
git commit -m "fix(airag): 图片上传失败不再产生裂图，异步海报不再返回假成功"
```

---

### Task 7: 统一提交测试并做全量验证（提交 7）

**Files:**
- Commit: `src/test/java/org/jeecg/modules/airag/llm/handler/LlmErrorMessageTranslatorTest.java`
- Commit: `src/test/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImplSseTest.java`
- Commit: `src/test/java/org/jeecg/modules/airag/llm/handler/AIChatHandlerTimeoutTest.java`

- [ ] **Step 1: 全量跑本方案全部测试类**

Run: `mvn -pl jeecg-boot-module/jeecg-boot-module-airag test -DskipTests=false -Dsurefire.failIfNoSpecifiedTests=false -Dtest='LlmErrorMessageTranslatorTest,AiragChatServiceImplSseTest,AIChatHandlerTimeoutTest,AiragChatControllerTest'`

Expected: 四个测试类全部执行，`Failures: 0, Errors: 0`，`BUILD SUCCESS`。若 `AiragChatServiceImplSseTest` 中某个 case 偶发失败，先重跑一次排除环境抖动；仍失败则回到对应 Task 检查实现。

- [ ] **Step 2: 提交测试文件**

```bash
cd D:/carsmaga/JeecgBoot
git add jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/handler/LlmErrorMessageTranslatorTest.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/app/service/impl/AiragChatServiceImplSseTest.java \
        jeecg-boot/jeecg-boot-module/jeecg-boot-module-airag/src/test/java/org/jeecg/modules/airag/llm/handler/AIChatHandlerTimeoutTest.java
git commit -m "test(airag): 补SSE错误路径、超时透传与错误文案翻译单测"
```

- [ ] **Step 3: 最终状态核对**

```bash
cd D:/carsmaga/JeecgBoot
git status --short
git log --oneline -8
```

Expected:
- `git status` 只剩任务开始时就存在的 4 个 `M` 文件与 `?? start.md`（如有测试 target 产物被忽略则无额外条目）；
- `git log` 顶部依次为上述 7 个提交（最新是 test 提交）。

---

## 附录 A：手动端到端回归（不提交任何文件）

自动化测试覆盖不到真实网络链路，发版前按下面步骤手动验证一次。

1. 准备错误网关（脚本放仓库外，如 `D:\tmp\airag-err-gateway\gateway.py`；用 `python -I gateway.py` 运行，监听 127.0.0.1:8099）：

```python
# 用途：模拟上游返回 HTML 502 页面（正文含小写 "error"），复现「onError JSON 解析失败导致挂起」
from http.server import BaseHTTPRequestHandler, HTTPServer

BODY = b"""<html><head><title>502 Bad Gateway</title></head>
<body><h1>502 Bad Gateway</h1><p class="error-details">upstream connect error</p></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def _respond(self):
        self.send_response(502)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    do_GET = _respond
    do_POST = _respond

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    HTTPServer(("127.0.0.1", 8099), Handler).serve_forever()
```

2. 把默认模型地址临时指向该网关：修改 `jeecg-boot/jeecg-module-system/jeecg-system-start/src/main/resources/application-dev.yml` 的 `jeecg.ai-chat.api-host` 为 `http://127.0.0.1:8099/v1`。**只改这一行，验证后立即还原且不提交**；该文件当前带有未提交改动，注意不要覆盖。
3. 用默认助手或任意分享应用发一条消息（DevTools 或 `curl -N` 观察 SSE 流）。
4. 期望：修复前流永不结束（页面一直"生成中"）；修复后收到 `"event":"ERROR"` 且带可读中文文案，流正常关闭。随后查 Redis，`CHAT:TYPE:SSE*`/`CHAT:TYPE:SSE_SEND_TIME*`/`CHAT:TYPE:SSE_HISTORY_MSG*` 三个键均已不存在（本机 6379 是 ai-redis 容器、无密码）。
5. 验证超时：把网关改成"接受连接但不返回任何数据"（去掉响应体写入），期望约 300 秒后收到超时提示，而不是等 41.7 小时。

## 附录 B：设计文档对照（自检用）

| 设计条目 | 落点 |
|---|---|
| §4.1 翻译器 + 3 个调用点 | Task 1（translateLlmException）、Task 2（onError）、Task 4（sendWithDefault catch） |
| §4.2 closeSSE/createSSE/cleanup + 60 分钟硬超时 | Task 2 |
| §4.3 删除 setTimeout + mergeParams 规范化 | Task 3 |
| §4.4 sendWithDefault 不 rethrow + genAiWriter try/catch | Task 4 |
| §4.5 completions/imageEdit/uploadImage/genImageChat/genAiPoster | Task 5、Task 6 |
| §5.1 三个测试类 | Task 1 / Task 2 / Task 3 创建，Task 7 提交 |
| §6 七个提交 | Task 1~7，翻译器提交提到最前 |
| 验收不变量 A/B/C/D | A、B → SSE 测试；C → 翻译器测试；D → 超时测试 + SSE 透传用例 |

补充说明：设计文档 §5.1 表格中"超时上限（99999→1800）"一条，本计划落在 `AIChatHandlerTimeoutTest`（Task 3）而非 SSE 测试——SSE 测试里 `aiChatHandler` 是 mock，超时钳制发生在真实的 `AIChatHandler.mergeParams` 内，只有对真实 handler 的测试才能验证；SSE 测试负责验证"服务层不再覆写应用级超时"。

---

## 勘误 R1（2026-11-05，Task 2 执行中发现并处理完毕）

**问题**：Task 2 Step 1 内嵌的 `AiragChatServiceImplSseTest.java`（313 行版）在本仓库上从未可运行：`AiragChatServiceImpl` 带 Lombok `@RequiredArgsConstructor`（该类第 97 行，覆盖 2 个 final 字段），Mockito 5.23.0 的 `@InjectMocks` 命中"最大构造器"注入后策略链短路，其余 14 个 `@Autowired` 非 final 字段保持 null；4 个用例在"修复前红"与"修复后绿"两轮都死在 `performSend()` 的 `airagAppMapper is null` NPE（与主代码改动无关）。证据：`/tmp/sse-step2.log`、`/tmp/sse-step8.log`。

**处置**：先用修补副本做离线等价验证（真实类路径 + STRICT_STUBS，`SUMMARY pass=4 fail=0`，`/tmp/run3.log`），再授权对测试文件做唯一勘误改动——`setUp()` 开头插入 15 行（1 行中文注释 + 14 行 `ReflectionTestUtils.setField`）：

```java
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
```

测试方法体与断言零改动；该文件保持未跟踪，留待 Task 7 统一提交。编辑前基线副本：`/tmp/SseTest-before.java`（md5 `a4b2a456c5f9a37b62d8341e49ca1825`）。

**结果**：Step 8 全绿（`Tests run: 4, Failures: 0, Errors: 0` + BUILD SUCCESS）；Task 2 提交 `976ac7d6`（只含 `AiragChatServiceImpl.java`，80+/41-）；测试文件最终 md5 `afafa5a14c196efc5af3c3c84177821f`（328 行、LF、无 BOM）。

**对后续影响**：无。Task 3 追加用例、Task 4/7 回归均基于修好的 harness，后续任务文本无需改动。
