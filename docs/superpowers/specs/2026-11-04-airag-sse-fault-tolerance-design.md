# AI 模块容错加固设计（SSE 会话挂起与错误提示丢失）

- 日期：2026-11-04
- 范围：`jeecg-boot-module-airag`，聚焦 AI 聊天主链路，附带图片/海报路径
- 分支：`fix/airag-fault-tolerance`
- 状态：待评审

## 1. 背景与根因

### 1.1 现象

用户在 AI 应用里发消息后，前端长期停在“生成中”，不出现任何错误提示；点“继续接收”也拿不到结束事件，通常只能重启服务恢复。后台日志里能看到 `onError` 回调内部抛出的异常，langchain4j 只会打印两行日志（`While handling the following error...` 与 `...the following error happened`）。

前端侧已核实：`jeecgboot-vue3/src/views/super/airag/aiapp/chat/chat.vue` 的 `renderChatByResult` 是一个 `while(true)` 读流循环，只有流结束（`done`）才退出，循环内没有客户端看门狗。所以“服务端不关流”等于“前端一直转圈”。同一文件已经有 `getChatErrorMessage` + `updateChatFail` 的错误渲染能力，因此**只要后端把 ERROR 事件发出来，用户就能看到错误，前端无需改动**。

### 1.2 根因（均已核实到代码或字节码）

**根因 1：错误回调自身会抛异常，且没有任何兜底**

`AiragChatServiceImpl.onError`（改造前第 1779-1822 行）的 else 分支存在两个必炸点：

1. `error.getMessage().contains(key)` 未判空，message 为 null 直接 NPE。
2. 只要 message 含小写 `error`，就无条件执行 `JSONObject.parseObject(message).get("error").toString()`：
   - 上游返回 HTML 错误页或纯文本（如网关 502 页面）时 `parseObject` 抛 JSONException；
   - JSON 合法但没有 `error` 键时（如千帆 `{"error_code":110,"error_msg":"..."}`）`get("error")` 返回 null，`.toString()` 抛 NPE。

库层行为（已读 langchain4j 1.17.2 源码确认）：

- `InternalStreamingChatResponseHandlerUtils.onCompleteResponse`（源码 141-147 行）会把 `onCompleteResponse` 回调里的异常转交 `handler.onError`，所以正常路径是能兜住的；
- 但 `AiServiceStreamingResponseHandler.onError`（源码 531-543 行）只把 `onError` 回调自身的异常打日志吞掉，不会再有人关闭流；
- `AiragChatServiceImpl.createSSE` 使用 `new SseEmitter(-0L)`，超时为 0，Servlet 语义下永不超时，没有任何兜底网。

**根因 2：历史事件缓存从不清理**

`CACHE_TYPE_SSE_HISTORY_MSG` 在代码里只有 `put` 与 `get`，没有任何 `remove`；`CACHE_TYPE_SSE` 与 `CACHE_TYPE_SSE_SEND_TIME` 只在 `closeSSE` 与 `receiveByRequestId` 循环内清理。只要有任何一条路径漏掉 `closeSSE`，三者都会残留。

**根因 3：超时单位错误，且覆盖了应用级配置**

`AiragChatServiceImpl` 改造前第 1577 行 `aiChatParams.setTimeout(5 * 30 * 1000)`。链路已用字节码核实：

| 环节 | 事实 |
|---|---|
| `AIChatParams extends AIParams` | `AIParams.timeout` 是 `Integer` |
| `AIParams.toModelOptions()` | 调用 `AiModelOptionsBuilder.timeout(Integer)` |
| `AiModelFactory` | 读取 `AiModelOptions.getTimeout()`，转换只用 `Duration.ofSeconds`（该文件 25 处 ofSeconds、0 处 ofMillis） |
| `AiModelFactory.pickHttpClientBuilder` | 仅当 `izHttpVersionOne=true` 用 JDK HttpClient，默认走 OkHttp |
| `AiModelFactory.buildOkHttpClientBuilder(int)` | 同一个值同时设置 `connectTimeout` 与 `readTimeout` |

因此 `5*30*1000` 的实际语义是 **150000 秒 ≈ 41.7 小时**，同时把第 1351-1353 行从应用 metadata 读取的超时（默认 300 秒）变成了死配置。由于默认客户端是 OkHttp，`readTimeout` 是“两次读取之间的最大空闲时间”，这条缺陷直接导致**上游挂起后既不超时也不报错**。

**根因 4：失败路径不关闭 SSE**

- `genAiWriter`（改造前 2498-2543 行）直接调用 `sendWithFlow`，方法体与 `AiragChatController.genAiWriter` 都没有 try/catch。`runFlow` 同步抛异常时 emitter 已写入 `AiragLocalCache`，异常冒到 Spring 变成 500 JSON，缓存无人清理。
- `sendWithDefault` 的 catch 分支先 `closeSSE` 又 `throw new JeecgBootBizTipException`，由 `doChat` 的 `catch (Throwable)` 二次关闭。二次关闭本身无害，但让日志与语义变浑浊。

**根因 5：零散缺陷**

| 位置 | 问题 |
|---|---|
| `AIChatHandler.completions(String modelId, ...)` | 模型 id 不存在或未激活时 `airagModel.getModelName()` NPE（第 124 行）；`chat()` 有默认模型回退而 `completions()` 没有 |
| `AIChatHandler.imageEdit(AiragModel, ...)` | 第 622 行 `airagModel.getActivateFlag() == 0` 未判空，`imageGenerate` 第 581 行判了 |
| `AiragChatServiceImpl.uploadImage` | 失败返回空串，调用方拼出 `![]()`，用户只看到裂图 |
| `AiragChatServiceImpl.genAiPosterAsync` | 上传全失败时仍写入 `status=success` 且 `imageUrl=""`，前端轮询到“成功”却无图 |

**已知但无法核实的两点**（不在本次修复范围，文档留档）：

- `AiragLocalCache` 的真实实现被加密在 `META-INF/.templates/184a9b68a28ac9646bfc0d02c2d92141`（32 字节随机文件头，非 class 魔数），无法确认 Guava Cache 是否配置了 TTL。因此缓存清理必须由代码保证，不能依赖 TTL。
- `AiragFlowServiceImpl.runFlow` 在加密的 `jeecg-aiflow-boot4` 内，`sendWithFlow` 除 LLM 超时外的失败处理无法核实。

## 2. 目标与非目标

**目标**

1. 任何异常路径下，客户端都必定收到结束事件，流必定关闭。
2. 任何请求结束后，三个 SSE 缓存键都不残留。
3. 面向用户的错误文案对 null message、HTML、缺键 JSON 都安全，且尽量给出可操作的中文提示。
4. 超时恢复正确语义，应用级配置重新生效。
5. 关键缺陷有可回归的自动化测试。

**非目标**

1. 不重构 SSE 生命周期为独立封装类（评估过，收益小于改动面）。
2. 不修复 `stop()` 无法真正中断 langchain4j 流的问题（需要改用 `onPartialResponseWithContext` + `StreamingHandle.cancel()`，单独立项）。
3. 不改 `AiragAppServiceImpl.startLLMStream` 的同类脆弱回调（仅影响“变量/记忆指南生成”入口，且其 `closeSSE` 在 finally 中已能完成清理），留档。
4. 不改前端。

## 3. 验收不变量

| 编号 | 不变量 | 验证方式 |
|---|---|---|
| A | 请求结束后 `CACHE_TYPE_SSE`、`CACHE_TYPE_SSE_SEND_TIME`、`CACHE_TYPE_SSE_HISTORY_MSG` 均不存在 | 单测断言 + 手动复现后查 Redis |
| B | 任意异常路径下客户端收到 `MESSAGE_END` 或 `ERROR` 事件，且 HTTP 流结束 | MockMvc 单测 + `curl -N` |
| C | 翻译函数对 null message、HTML 正文、缺 `error` 键 JSON、含 `error` 键 JSON、中文/英文混合输入都不抛异常 | `LlmErrorMessageTranslatorTest` |
| D | 未配置超时时实际值为 300 秒；配置值被限制在 [30, 1800] 秒；应用 metadata 优先于模型参数 | 参数捕获单测 |

## 4. 设计

### 4.1 新增：错误文案翻译器

新增 `org.jeecg.modules.airag.llm.handler.LlmErrorMessageTranslator`，纯静态、无状态、可单测：

```java
public final class LlmErrorMessageTranslator {
    public static String translate(Throwable error);      // onError 入口，内部取 message 并兜底
    public static String translateMessage(String message); // 纯函数，便于直接单测
}
```

匹配顺序（自上而下，命中即返回）：

1. message 为空 → 默认文案 `调用大模型接口失败，详情请查看后台日志。`
2. 忽略大小写匹配 `IAIChatHandler.MODEL_ERROR_MAP`（复用 jar 内已有的 19 条映射，上游新增条目自动生效；该 map 在 jar 内以匿名 `Map` 子类初始化，键值已核实）
3. 忽略大小写包含 `timeout` 或 `timed out`（补齐 langchain4j/OkHttp 实际使用的 “Read timed out” 措辞）→ `当前用户较多，排队中，请稍后再试！`
4. 尝试 `JSONObject.parseObject(message)`：
   - 成功且 `error` 为对象 → 优先取其中的 `message` 字段，缺失则取整个 `error` 的 JSON 串；
   - 成功且 `error` 为字符串 → 直接使用；
   - 成功但无 `error` 键 → 依次尝试 `error_msg`、`message`；
   - 解析失败（HTML、纯文本）→ 跳过。
5. 结果截断到 200 字，仍为空则回默认文案。

约束：翻译结果只用于**发给用户的文案**；完整堆栈继续由 `log.error(..., error)` 输出，二者不混用。

调用点统一为三处：`AiragChatServiceImpl.onError`、`AiragChatServiceImpl.sendWithDefault` 的 catch（替换内联的 `instanceof` 判断）、`AIChatHandler.translateLlmException`（替换对 jar 内 `matchErrorMsg` 的依赖，顺带获得 JSON 解析能力）。

### 4.2 SSE 生命周期兜底

改 `AiragChatServiceImpl` 的两个私有方法，不改调用方：

**`closeSSE(SseEmitter, EventData)`**

- 整个方法体包 try/catch/finally；
- 判空 `eventData`、判空 `eventData.getRequestId()`；
- “已经完成”的判定改为 `contains()` 的空安全写法（当前 `e.getMessage().contains(...)` 在 message 为 null 时会 NPE）；
- `completeWithError` 只在其自身 try/catch 内调用，行为保持现状（仅在错误事件发送失败时使用）；
- **finally 中统一清理三个缓存键**并 `emitter.complete()`。

**`createSSE(String requestId)`**

- 保留现有 `onError` 回调，但把清理逻辑收敛到同一个 `cleanupSseCache(requestId)` 私有方法；
- 新增 `emitter.onCompletion(...)` 与 `emitter.onTimeout(...)`，两者都调用 `cleanupSseCache`。这样即使某个回调在关闭前抛异常、或客户端主动断开、或容器超时，缓存也不会残留；
- 硬超时从 `-0L`（永不超时）改为 `SSE_MAX_DURATION_MS = 60 分钟` 常量，作为“最后一道兜底网”：容器在 60 分钟后强制结束异步请求，触发 `onTimeout` 完成清理并给客户端发一条 `ERROR`（文案：`本次会话超时已结束，请重新发起对话。`）。

风险与取舍：60 分钟是**硬上限**，数据持续输出也不会延长。当前没有任何兜底、挂起即永久，因此这是净改进；若后续出现合法的超长会话需求，再把该常量提为配置项（本次不引入配置项，避免配置面膨胀）。

### 4.3 超时策略

- 删除 `AiragChatServiceImpl.sendWithDefault` 中的 `aiChatParams.setTimeout(5 * 30 * 1000)`。
- 在 `AIChatHandler.mergeParams` 结尾统一规范化 `timeout`（单点，覆盖应用 metadata 与模型参数两个来源）：
  - `timeout` 为空 → `AiragConsts.DEFAULT_TIMEOUT`（300 秒）；
  - `timeout < 30` → 30 秒；`timeout > 1800` → 1800 秒，并 `log.warn` 记录被修正的原始值；
  - 日志中输出最终生效值，便于排障。
- 优先级（与现状一致，只是不再被覆盖）：应用 `metadata.timeout` → 模型 `modelParams.timeout` → 默认 300 秒。

效果：上游建连或无数据超过 300 秒即失败，由修好的 `onError` 给出友好文案并关闭 SSE。

### 4.4 失败路径统一

| 位置 | 改法 |
|---|---|
| `sendWithDefault` catch 分支 | 关闭 SSE 后**不再 rethrow**，直接 return；文案改用翻译器；`doChat` 的 `catch (Throwable)` 保留为最外层兜底 |
| `genAiWriter` | 把 `sendWithFlow` 调用包进 try/catch：失败时发 `EVENT_FLOW_ERROR`（文案走翻译器）并 `closeSSE`；异常不再冒到 Spring |
| `sendWithFlow` 的非超时失败 | 保持现状只依赖流程内部事件（流程实现不可核实），由 4.2 的 `onTimeout`/`onCompletion` 兜住缓存清理与最终关闭 |

### 4.5 零散缺陷

| 位置 | 改法 |
|---|---|
| `AIChatHandler.completions(String, List)` | 与 `chat()` 对齐：模型不存在或 `activateFlag == 0` 时回退 `completionsByDefaultModel`，并 `log.warn` |
| `AIChatHandler.imageEdit(AiragModel, ...)` | `activateFlag` 判空，与 `imageGenerate` 同口径 |
| `AiragChatServiceImpl.uploadImage` | 保持返回空串的契约不变；调用方过滤空值 |
| `AiragChatServiceImpl.genImageChat` | 过滤空 URL；全部为空时不追加空的 AiMessage，改发 `EVENT_FLOW_ERROR`（文案：`图片生成失败：图片上传失败，请检查存储配置。`） |
| `AiragChatServiceImpl.genAiPoster` | 结果为空时抛 `JeecgBootBizTipException`，让异步任务落到 failed，不再假成功 |
| `ImageGenerationToolBuilder` | 不改（已有 `isNotEmpty` 过滤与结构化错误返回），仅在文档说明 |

## 5. 测试方案

### 5.1 自动化单测（新增）

**`LlmErrorMessageTranslatorTest`**（纯函数，无 Spring 上下文）

| 用例 | 输入 | 期望 |
|---|---|---|
| null message | `new RuntimeException()` | 默认文案 |
| HTML 网关页 | `"<html>...502 Bad Gateway...error...</html>"` | 默认文案，不抛异常 |
| 缺 error 键 | `"{\"error_code\":110,\"error_msg\":\"Access token invalid\"}"` | `Access token invalid` |
| 含 error 键 | `"{\"error\":{\"message\":\"Insufficient Balance\"}}"` | 命中余额不足文案 |
| 超时措辞 | `"java.net.SocketTimeoutException: Read timed out"` | 排队提示 |
| 大小写 | `"INVALID_API_KEY"` | 无效的 API 密钥 |

**`AiragChatServiceImplSseTest`**（MockMvc standalone + 真实 Service + mock 协作者，沿用现有 `AiragChatControllerTest` 的写法）

事件常量取值已核实：`EVENT_FLOW_ERROR` = `"ERROR"`、`EVENT_MESSAGE_END` = `"MESSAGE_END"`、`EVENT_INIT_REQUEST_ID` = `"INIT_REQUEST_ID"`、`EVENT_MESSAGE` = `"MESSAGE"`（均取自 `org.jeecg.modules.airag.common.vo.event.EventData`）。

| 用例 | 断言 |
|---|---|
| 错误事件含 null message 的异常 | 响应体含 `"event":"ERROR"` 与默认文案，请求正常结束 |
| 错误事件含 `"error"` 的 HTML 正文 | 同上（**修复前该用例会挂住，是本次回归护栏**） |
| 错误事件含无 `error` 键的 JSON | 同上 |
| 正常 STOP 结束 | 响应体含 `"event":"MESSAGE_END"`，三个缓存键均不存在 |
| 应用级超时透传 | 捕获 `aiChatHandler.chat(...)` 的 `AIChatParams`，断言 timeout 等于应用 metadata 配置值（例如 120），而非 150000 |
| 超时上限 | metadata 配 99999 时断言实际为 1800 |

缓存断言方式：从响应体中解析 `requestId`（`EVENT_INIT_REQUEST_ID` 事件带出），再断言 `AiragLocalCache.get(...)` 三个键为 null。

复用 `AiragLocalCache` 的静态特性可直接在测试中读取，无需引入新接口。

### 5.2 手动复现（端到端）

1. 起一个只返回 HTML 502 的极简网关（脚本放仓库外，如 `D:\tmp\airag-err-gateway\gateway.py`，用 `python -I gateway.py` 运行，监听 127.0.0.1:8099）。脚本内容见附录 B。OkHttp 的 SSE 路径已核实：非 2xx 会构造 `HttpException(code, body)` 交给 `onError`，所以 HTML 正文会原样进入错误消息，正是触发根因 1 的输入。
2. 把**默认模型**地址临时指向该网关：修改 `application-dev.yml` 的 `jeecg.ai-chat.api-host` 为 `http://127.0.0.1:8099/v1`。只改这一行，验证后立即还原且不提交；该文件当前带有未提交改动，注意不要覆盖。
   不采用“给 AI 应用绑定一个坏模型”的做法：`AIChatHandler.chat` 对未激活或不存在的模型会回退到默认模型，绑不到坏模型上。
3. 用默认助手或任意“模型未激活”的应用发一条消息，用浏览器 DevTools 或 `curl -N` 观察 SSE 流。若希望走数据库里真实配置的模型，可临时把某个已激活模型的 `base_url` 指向网关，但修改库数据需要你确认授权。
4. 期望：修复前流永不结束（页面一直“生成中”）；修复后收到 `"event":"ERROR"` 且带可读中文文案，流正常关闭。随后查 Redis，`airag` 的三个 SSE 键均已不存在。
5. 补充验证超时：把网关改成“接受连接但不返回任何数据”（去掉响应体写入即可），期望约 300 秒后收到超时提示，而不是等 41.7 小时。

### 5.3 验证命令

```bash
export JAVA_HOME=C:/java/Java/jdk-21          # 本机全局 JAVA_HOME 仍是 JDK 8
cd D:/carsmaga/JeecgBoot/jeecg-boot
mvn -pl jeecg-boot-module/jeecg-boot-module-airag -am test -DskipTests=false \
    -Dtest='LlmErrorMessageTranslatorTest,AiragChatServiceImplSseTest,AiragChatControllerTest'
```

## 6. 交付方式

- 分支：`fix/airag-fault-tolerance`（已创建，工作区现有 4 个未提交改动保持原样不参与本次提交）
- 提交划分（按缺陷，便于单独回滚）：

| 序号 | 提交信息 |
|---|---|
| 1 | `fix(airag): SSE错误回调异常导致会话永久挂起，onError全兜底并统一清理缓存` |
| 2 | `fix(airag): 修正AI聊天超时单位错误（150000秒），恢复应用级超时配置与上限保护` |
| 3 | `fix(airag): 抽出大模型错误文案翻译器，null与非JSON响应不再丢失错误提示` |
| 4 | `fix(airag): genAiWriter与聊天失败路径统一关闭SSE并清理缓存` |
| 5 | `fix(airag): 模型未激活或不存在时completions回退默认模型，补齐activateFlag判空` |
| 6 | `fix(airag): 图片上传失败不再产生裂图，异步海报不再返回假成功` |
| 7 | `test(airag): 补SSE错误路径、超时透传与错误文案翻译单测` |

- 不新增数据库脚本、不新增配置项、不改前端，回滚只需 revert 对应提交。

## 7. 风险

| 风险 | 评估 |
|---|---|
| 60 分钟硬超时可能切断合法的超长会话 | 当前是永久挂起，属净改进；观察后可按需提为配置项 |
| 超时从 41.7 小时降到 300 秒，可能切断“上游 5 分钟无数据但最终会回复”的场景 | 属预期行为；若某上游确实慢，可在应用 metadata 里单独调大到上限 1800 秒 |
| 为 `createSSE` 增加 `onCompletion` 清理，会让“流结束后再调用 `/receive` 重放历史”不可用 | 现状 `closeSSE` 已删除 `CACHE_TYPE_SSE`，`receiveByRequestId` 开头就会返回 null，重放本来不可用，行为无变化 |
| 缓存清理依赖代码而非 TTL | `AiragLocalCache` 实现被加密，无法核实 TTL；本次以代码为准 |

## 附录 A：`MODEL_ERROR_MAP` 全部条目（jar 内反编译所得）

| 命中关键字（忽略大小写） | 用户文案 |
|---|---|
| size_not_supported | 当前模型不支持该图片尺寸，请更换尺寸后重试! |
| 请传递 size 参数 | 当前图片模型需要设置图片尺寸，请选择尺寸后重试! |
| 负载已饱和 | 当前模型服务负载已饱和，请稍后重试或更换模型通道! |
| Throttling.RateQuota | 当前模型调用频率已达上限，请稍后重试! |
| unknown_model | 上游未找到可用模型，请检查模型名称或更换模型通道! |
| upstream_error | 上游模型服务异常，请稍后重试或更换模型通道! |
| invalid_api_key | 无效的API密钥! |
| v_api_error | 无效的令牌! |
| Insufficient Balance / insufficient_balance | API账户余额不足，请充值后重试! |
| authentication_error | 认证错误，请检查API密钥或凭据或余额是否充足! |
| timeout | 当前用户较多，排队中，请稍后再试！ |
| not match any variant of untagged | 消息格式有误，请检查输入内容或选择支持该格式的模型。 |
| 请先登录 | 使用该功能需要您先登录账号，请先登录后再试。 |
| Connection reset / connection reset / Stream reset / GOAWAY / SocketException | 网络连接已断开（可能因长时间空闲被对端关闭），请重新发起对话。 |
| preceding message with 'tool_calls' | 消息序列不完整，可能是因为历史消息数量设置过小导致工具调用上下文丢失。建议增加历史消息数量后重试。 |

## 附录 B：手动复现用的错误网关脚本

```python
# D:\tmp\airag-err-gateway\gateway.py
# 用途：模拟上游返回 HTML 502 页面（正文含小写 "error"），复现「onError JSON 解析失败导致挂起」
# 运行：python -I gateway.py    监听 http://127.0.0.1:8099
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
