// package = 声明这个文件属于哪个包
package com.example.ccagent.service;

// import = 引入其他包的类
// java.net.URI = 统一资源标识符，用于表示 API 地址
import java.net.URI;
// java.net.http.HttpClient = Java 11+ 内置的 HTTP 客户端
import java.net.http.HttpClient;
// java.net.http.HttpRequest = HTTP 请求对象
import java.net.http.HttpRequest;
// java.net.http.HttpResponse = HTTP 响应对象
import java.net.http.HttpResponse;
// java.util.ArrayList = 可变长度的列表
import java.util.ArrayList;
// java.util.HashMap = 可变长度的键值对集合
import java.util.HashMap;
// java.util.List = 有序列表接口
import java.util.List;
// java.util.Map = 键值对集合接口
import java.util.Map;
// java.util.function.Consumer = 回调函数接口（接收一个值，做点什么）
import java.util.function.Consumer;
// java.io.BufferedReader = 按行读取字符流
import java.io.BufferedReader;
// java.io.InputStream = 字节输入流
import java.io.InputStream;
// java.io.InputStreamReader = 字节流转字符流
import java.io.InputStreamReader;
// java.io.FileWriter = 往文件写字符（这里用追加模式把非 data 行记进 sse_log.txt）
import java.io.FileWriter;
// java.io.IOException = IO 操作（读写文件/网络流）可能抛出的异常
import java.io.IOException;
// java.nio.charset.StandardCharsets = 字符编码常量，强制 UTF-8 解码
import java.nio.charset.StandardCharsets;

// import = 引入 Spring 的组件标记和依赖注入
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

// import = 引入项目内部的类
import com.example.ccagent.config.AgentProperties;
import com.example.ccagent.model.Message;
import com.example.ccagent.model.ToolCall;

// import = 引入 Jackson JSON 处理库
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

// import = 引入日志
// SLF4J 是接口，Logback 是实际实现（Spring Boot 自带）
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anthropic API 客户端
 *
 * 作用：和 Anthropic API 通信，发送对话历史，接收响应
 *
 * 工作流程：
 *   1. 接收对话历史（List<Message>）
 *   2. 转成 Anthropic API 需要的 JSON 格式
 *   3. 发送 HTTP POST 请求到 Anthropic API
 *   4. 接收响应 JSON
 *   5. 解析出文本内容和工具调用
 *   6. 返回 AnthropicResponse 对象
 */
// @Component = 标记为 Spring 管理的组件
@Component
public class AnthropicClient {

    // Logger = 日志对象，用于记录调试信息和错误
    // getLogger(AnthropicClient.class) = 日志会标注来自这个类
    private static final Logger log = LoggerFactory.getLogger(AnthropicClient.class);

    // @Autowired = 连接 Spring 容器里的 AgentProperties 实例到这个字段
    // AgentProperties 包含 API 地址、密钥、模型等配置
    @Autowired
    private AgentProperties properties;

    @Autowired
    private AgentConfigLoader agentConfig;

    // HttpClient = Java 内置的 HTTP 客户端，用于发送 HTTP 请求
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // ObjectMapper = Jackson 的核心类，用于 JSON 和 Java 对象之间的转换
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Anthropic API 响应数据类
     *
     * text = Anthropic 返回的文本内容
     * toolCalls = Anthropic 请求的工具调用列表（可能为空）
     * inputTokens/outputTokens = API 返回的真实 token 用量，可能为空
     */
    public record AnthropicResponse(
        String text,
        List<ToolCall> toolCalls,
        Integer inputTokens,
        Integer outputTokens
    ) {}

    /**
     * 兼容旧调用：不附加 AGENT.MD，max_tokens 默认 4096。
     */
    public AnthropicResponse chat(List<Message> history, List<Map<String, Object>> tools) throws Exception {
        return chat(history, tools, "", 4096);
    }

    /**
     * 兼容旧调用：指定 agentMemory，max_tokens 默认 4096。
     */
    public AnthropicResponse chat(List<Message> history, List<Map<String, Object>> tools, String agentMemory) throws Exception {
        return chat(history, tools, agentMemory, 4096);
    }

    /**
     * 和 Anthropic API 通信（完整参数版本）。
     *
     * @param history 对话历史（包含用户消息和之前的回复）
     * @param tools 工具定义列表（ToolRegistry.getToolDefinitions() 返回的）
     * @param agentMemory AGENT.MD 的内容（空字符串表示不附加）
     * @param maxTokens 最大输出 token 数（普通对话用 4096，压缩摘要用 512）
     * @return Anthropic 的响应（包含文本和/或工具调用）
     * @throws Exception API 调用失败时抛出异常
     */
    public AnthropicResponse chat(List<Message> history, List<Map<String, Object>> tools,
                                   String agentMemory, int maxTokens) throws Exception {
        return chat(history, tools, agentMemory, maxTokens, properties.apiKey());
    }

    public AnthropicResponse chat(List<Message> history, List<Map<String, Object>> tools,
                                   String agentMemory, int maxTokens, String apiKey) throws Exception {
        requireApiKey(apiKey);
        // 记录 API 调用开始
        long startMs = System.currentTimeMillis();
        List<Map<String, Object>> safeTools = tools == null ? List.of() : tools;
        String systemPrompt = buildSystemPrompt(agentMemory);
        log.info("调用 API，history 消息数: {}, 工具数: {}, agentMemoryChars: {}, systemPromptChars: {}, maxTokens: {}",
            history.size(), safeTools.size(), agentMemory == null ? 0 : agentMemory.length(),
            systemPrompt.length(), maxTokens);

        // 1. 构建请求体（Anthropic API 需要的 JSON 格式）
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", properties.model());
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("system", systemPrompt);
        // "messages" = 对话历史
        // toApiMessage() 把内部 Message 转成 Anthropic API 需要的 JSON 格式
        requestBody.put("messages", history.stream()
            .map(this::toApiMessage)
            .toList());
        // "tools" = 工具定义列表（告诉 Claude 有哪些工具可用）
        requestBody.put("tools", safeTools);

        // 2. 转成 JSON 字符串 -- chat()
        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 3. 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(properties.apiEndpoint()))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        // 4. 发送请求并接收响应
        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString());

        // 5. 只记录状态码。上游响应体可能包含敏感信息，不写入日志。
        log.info("API 响应状态码: {}", response.statusCode());

        // 6. 检查响应状态码
        if (response.statusCode() != 200) {
            throw new RuntimeException("API 调用失败，状态码: " + response.statusCode());
        }

        // 7. 解析响应 JSON（加 try-catch 防止格式不兼容）
        JsonNode responseJson;
        try {
            responseJson = objectMapper.readTree(response.body());
        } catch (Exception e) {
            log.error("API 响应 JSON 解析失败", e);
            throw new RuntimeException("API 响应 JSON 解析失败", e);
        }

        // 7. 提取文本内容和工具调用
        String text = "";
        List<ToolCall> toolCalls = new ArrayList<>();
        Integer inputTokens = readUsageInt(responseJson, "input_tokens");
        Integer outputTokens = readUsageInt(responseJson, "output_tokens");

        // responseJson.get("content") = 获取 content 数组
        JsonNode contentArray = responseJson.get("content");
        if (contentArray != null && contentArray.isArray()) {
            // 遍历 content 数组的每个元素
            for (JsonNode element : contentArray) {
                // element.get("type").asText() = 获取 type 字段的值
                String type = element.get("type").asText();

                if ("text".equals(type)) {
                    // 文本内容
                    text = element.get("text").asText();
                } else if ("tool_use".equals(type)) {
                    // 工具调用
                    // 解析工具调用的参数
                    Map<String, Object> input = new HashMap<>();
                    JsonNode inputNode = element.get("input");
                    if (inputNode != null && inputNode.isObject()) {
                        // 遍历 input 对象的每个字段
                        inputNode.fields().forEachRemaining(entry ->
                            input.put(entry.getKey(), entry.getValue().asText()));
                    }

                    // 创建 ToolCall 对象
                    toolCalls.add(new ToolCall(
                        element.get("id").asText(),
                        element.get("name").asText(),
                        input
                    ));
                }
            }
        }

        // 8. 记录 API 调用耗时和结果
        long elapsed = System.currentTimeMillis() - startMs;
        log.info("API 响应完成，文本长度: {}, 工具调用数: {}, inputTokens: {}, outputTokens: {}, 耗时: {}ms",
            text.length(), toolCalls.size(), inputTokens, outputTokens, elapsed);

        // 9. 返回结果
        return new AnthropicResponse(text, toolCalls, inputTokens, outputTokens);
    }

    // ==================== 流式方法 ====================

    /**
     * 和 Anthropic API 通信（流式版本）
     *
     * 和 chat() 的区别：不等全部返回，边收边处理。
     * 每收到一个 token，就调用 onToken.accept(token) 通知调用者。
     * 流结束后，返回完整的 AnthropicResponse（text + toolCalls）。
     *
     * @param history 对话历史
     * @param tools 工具定义列表
     * @param onToken 回调：每收到一个 token 就调用一次
     * @return 完整响应（文本 + 工具调用列表）
     * @throws Exception API 调用失败时抛出异常
     */
    public AnthropicResponse chatStream(
            List<Message> history,
            List<Map<String, Object>> tools,
            Consumer<String> onToken    // 回调函数：token -> { 做什么 }
    ) throws Exception {
        return chatStream(history, tools, "", onToken);
    }

    public AnthropicResponse chatStream(
            List<Message> history,
            List<Map<String, Object>> tools,
            String agentMemory,
            Consumer<String> onToken    // 回调函数：token -> { 做什么 }
    ) throws Exception {
        return chatStream(history, tools, agentMemory, properties.apiKey(), onToken);
    }

    public AnthropicResponse chatStream(
            List<Message> history,
            List<Map<String, Object>> tools,
            String agentMemory,
            String apiKey,
            Consumer<String> onToken
    ) throws Exception {
        requireApiKey(apiKey);

        // 记录流式 API 调用开始
        long startMs = System.currentTimeMillis();
        List<Map<String, Object>> safeTools = tools == null ? List.of() : tools;
        String systemPrompt = buildSystemPrompt(agentMemory);
        log.info("调用 API（流式），history 消息数: {}, 工具数: {}, agentMemoryChars: {}, systemPromptChars: {}, hasAgentMdRule: {}",
            history.size(), safeTools.size(), agentMemory == null ? 0 : agentMemory.length(),
            systemPrompt.length(), systemPrompt.contains("memory/AGENT.MD"));

        // 1. 构建请求体（和 chat() 一样，但是多加 stream: true）
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", properties.model());
        requestBody.put("max_tokens", 4096);
        // sysPrompt 是系统提示词，放到请求体顶层的 "system" 字段
        // 和 messages 数组是两个独立的东西，不会被用户消息替代
        requestBody.put("system", systemPrompt);
        requestBody.put("stream", true);  // ← 关键：启用流式
        requestBody.put("messages", history.stream()
            .map(this::toApiMessage)
            .toList());
        // "tools" = 工具定义列表
        requestBody.put("tools", safeTools);

        String jsonBody = objectMapper.writeValueAsString(requestBody);

        // 2. 构建 HTTP 请求
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(properties.apiEndpoint()))
            .header("Content-Type", "application/json")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();

        //加log，看看reques是什么
        log.info("HTTP 请求体前200字: {}",
            jsonBody.length() > 200 ? jsonBody.substring(0, 200) : jsonBody);

        // 3. 发请求，用 ofInputStream() 而不是 ofString()
        //    ofString() = 等全部返回才继续
        //    ofInputStream() = 返回一个流，可以边收边读
        HttpResponse<InputStream> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new RuntimeException("API 流式调用失败，状态码: " + response.statusCode());
        }

        // 4. 逐行读取 SSE 事件流
        //    InputStream = 原始字节流（一个字节一个字节读，不方便）
        //    InputStreamReader = 字节流转字符流
        //    BufferedReader = 可以按行读取（readLine() 一次读一行）
        // 显式指定 UTF-8 解码 DeepSeek 返回的 SSE 字节流。
        // 不指定时 InputStreamReader 用 JVM 默认编码，Windows 上是 GBK，中文 token 会变乱码。
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(response.body(), StandardCharsets.UTF_8));

        // 5. 准备收集结果
        StringBuilder fullText = new StringBuilder();  // 累积全部文本
        List<ToolCall> toolCalls = new ArrayList<>();  // 累积工具调用
        Integer inputTokens = null;
        Integer outputTokens = null;

        // 当前正在接收的工具调用（跨多行累积 partial_json）
        String currentToolId = null;
        String currentToolName = null;
        StringBuilder currentToolInput = new StringBuilder();

        String line;
        while ((line = reader.readLine()) != null) {

            // 只看 "data:" 开头的行（SSE 事件的负载）
            if (!line.startsWith("data: ")) {
                if (line.isEmpty()
                        || "event: content_block_delta".equals(line)
                        || "event: message_stop".equals(line)) {
                    continue;
                }

                //文件保存下来，加个log看看是什么样子，以后要进行相应的解析
                log.info("收到非 data 行: {}", line);
                // 可以选择将这些行保存到文件中，用于后续分析
                //写文件 "sse_log.txt"，追加模式
                try (FileWriter fw = new FileWriter("logs/sse_log.txt", true)) {
                    fw.write(line + "\n");
                } catch (IOException e) {
                    log.error("写入 SSE 日志文件时出错", e);
                }
                continue;
            }

            String json = line.substring(6);  // 去掉 "data: " 前缀（6 个字符）
            JsonNode node = objectMapper.readTree(json);
            String type = node.get("type").asText();

            Integer nodeInputTokens = readUsageInt(node, "input_tokens");
            if (nodeInputTokens == null && node.has("message")) {
                nodeInputTokens = readUsageInt(node.get("message"), "input_tokens");
            }
            if (nodeInputTokens != null) {
                inputTokens = nodeInputTokens;
            }

            Integer nodeOutputTokens = readUsageInt(node, "output_tokens");
            if (nodeOutputTokens == null && node.has("message")) {
                nodeOutputTokens = readUsageInt(node.get("message"), "output_tokens");
            }
            if (nodeOutputTokens != null) {
                outputTokens = nodeOutputTokens;
            }

            // ---- 处理 content_block_delta ----
            if ("content_block_delta".equals(type)) {
                JsonNode delta = node.get("delta");
                String deltaType = delta.get("type").asText();

                if ("text_delta".equals(deltaType)) {
                    // 收到一个文本 token
                    String token = delta.get("text").asText();
                    onToken.accept(token);      // → 回调：通知调用者
                    fullText.append(token);     // 累积到完整文本
                }
                else if ("input_json_delta".equals(deltaType)) {
                    // 收到工具调用的参数（可能分多行来）
                    currentToolInput.append(delta.get("partial_json").asText());
                }
            }
            // ---- 处理 content_block_start ----
            else if ("content_block_start".equals(type)) {
                JsonNode block = node.get("content_block");
                //加log看看content_block_start是什么样子的
                log.info("收到 content_block_start: {}", block.toString());
                if ("tool_use".equals(block.get("type").asText())) {
                    // 开始接收一个新的工具调用
                    currentToolId = block.get("id").asText();
                    currentToolName = block.get("name").asText();
                    currentToolInput = new StringBuilder();
                }
            }
            // ---- 处理 content_block_stop ----
            else if ("content_block_stop".equals(type)) {
                // 当前内容块结束
                if (currentToolId != null) {   // 如果 currentToolId 不为 null，说明刚结束一个工具调用的参数接收
                    // 工具调用参数接收完毕，解析 JSON 并创建 ToolCall
                    String inputJson = currentToolInput.toString();
                    //加log看看工具调用的参数是什么样子的
                    log.info("工具调用参数 JSON: {}", inputJson);
                    Map<String, Object> input = new HashMap<>();
                    if (!inputJson.isEmpty()) {
                        JsonNode inputNode = objectMapper.readTree(inputJson);
                        // DeepSeek 有时会用一层多余的 "input" 键把真正的参数包起来，
                        // 而且包装方式有「两种变体」，必须都解开，否则工具取不到 file_path/content：
                        //就是两种json格式：
                        //   变体1（值是对象）  ：{"input": {"file_path": "a.py", "content": "..."}}
                        //   变体2（值是字符串）：{"input": "{\"file_path\": \"a.py\", \"content\": \"...\"}"}
                        //                         ↑ input 的值是一段「JSON 文本」，要再 parse 一次才能拿到对象
                        // 解包后统一得到扁平的 {file_path, content} 交给工具。
                        // （之前只判断了变体1的 isObject()，碰到变体2会把整段 JSON 字符串原样塞进
                        //   "input" 键，导致工具 get("file_path") 取到 null，进而 NPE，这就是那条
                        //   「执行失败: null」的根源。）
                        JsonNode effective = inputNode;
                        if (inputNode.has("input")) {
                            JsonNode wrapped = inputNode.get("input");
                            if (wrapped.isObject()) {
                                effective = wrapped;
                            } else if (wrapped.isTextual()) {
                                String wrappedText = wrapped.asText();
                                if (wrappedText.trim().startsWith("{")) {
                                    effective = objectMapper.readTree(wrappedText);
                                } else {
                                    input.put("path", wrappedText);
                                    effective = null;
                                }
                            }
                        }

                        if (effective != null && effective.isObject()) {
                            effective.fields().forEachRemaining(entry ->
                                input.put(entry.getKey(), entry.getValue().asText()));
                        }
                    }
                    toolCalls.add(new ToolCall(currentToolId, currentToolName, input));
                    currentToolId = null;
                }
            }
        }

        // 7. 记录流式 API 调用耗时和结果
        long elapsed = System.currentTimeMillis() - startMs;
        log.info("流式 API 完成，文本长度: {}, 工具调用数: {}, inputTokens: {}, outputTokens: {}, 耗时: {}ms",
            fullText.length(), toolCalls.size(), inputTokens, outputTokens, elapsed);

        // 8. 返回完整结果
        return new AnthropicResponse(fullText.toString(), toolCalls, inputTokens, outputTokens);
    }

    // ==================== 内部转换方法 ====================

    private String buildSystemPrompt(String agentMemory) {
        String base = agentConfig.systemPrompt();
        StringBuilder system = new StringBuilder(base);
        system.append("\n\n");
        system.append(agentConfig.agentMemoryRule());
        if (agentMemory != null && !agentMemory.isBlank()) {
            system.append("\n\n当前 memory/AGENT.MD 内容：\n");
            system.append(agentMemory);
        }
        return system.toString();
    }

    /**
     * 把内部 Message 转成 Anthropic API 需要的 JSON 格式
     *
     * 文本：{"type": "text", "text": "..."}
     * 工具调用：{"type": "tool_use", "id": "...", "name": "...", "input": {...}}
     * 工具结果：{"role": "user", "content": [{"type": "tool_result", "tool_use_id": "...", "content": "..."}]}
     *
     * @param m 内部 Message 对象
     * @return API 格式的 Map（会被 Jackson 序列化成 JSON）
     */
    private Map<String, Object> toApiMessage(Message m) {
        List<Map<String, Object>> contentBlocks = new ArrayList<>();

        for (Message.ContentBlock block : m.contentBlocks()) {
            if (block instanceof Message.TextBlock textBlock) {
                Map<String, Object> text = new HashMap<>();
                text.put("type", "text");
                text.put("text", textBlock.text() != null ? textBlock.text() : "");
                contentBlocks.add(text);
            } else if (block instanceof Message.ToolUseBlock toolUseBlock) {
                Map<String, Object> toolUse = new HashMap<>();
                toolUse.put("type", "tool_use");
                toolUse.put("id", toolUseBlock.id());
                toolUse.put("name", toolUseBlock.name());
                toolUse.put("input", toolUseBlock.input() != null ? toolUseBlock.input() : Map.of());
                contentBlocks.add(toolUse);
            } else if (block instanceof Message.ToolResultBlock toolResultBlock) {
                Map<String, Object> toolResult = new HashMap<>();
                toolResult.put("type", "tool_result");
                toolResult.put("tool_use_id", toolResultBlock.toolUseId());
                toolResult.put("content", toolResultBlock.content() != null ? toolResultBlock.content() : "");
                if (toolResultBlock.isError()) {
                    toolResult.put("is_error", true);
                }
                contentBlocks.add(toolResult);
            }
        }

        Map<String, Object> msg = new HashMap<>();
        msg.put("role", m.role());
        msg.put("content", contentBlocks);
        return msg;
    }

    private Integer readUsageInt(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode usage = node.get("usage");
        if (usage == null || usage.isNull()) {
            return null;
        }
        JsonNode value = usage.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asInt();
    }

    private void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("请先在网页配置 DeepSeek API Key，或设置 DEEPSEEK_API_KEY 环境变量");
        }
        if (apiKey.length() > 512 || apiKey.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalStateException("DeepSeek API Key 格式无效");
        }
    }
}
