// package = 声明这个文件属于哪个包
package com.example.ccagent.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.example.ccagent.config.AgentProperties;
import com.example.ccagent.model.ChatResponse;
import com.example.ccagent.model.Compressor;
import com.example.ccagent.model.Message;
import com.example.ccagent.model.ToolCall;
import com.example.ccagent.model.ToolResult;

// Jackson 把每个 token 包成 JSON 字符串再发，避免 SSE 拆行问题：
// SSE 规范禁止 data: 字段含 \n，token 里的真换行会被 Tomcat 拆成多个 data: 行，
// 前端按行解析时换行就丢了。JSON 编码会把 \n 转义成字面量 \n，前端 JSON.parse 还原。
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    @Autowired
    private AnthropicClient anthropicClient;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired
    private AgentConfigLoader agentConfig;

    @Autowired
    private StreamFlowLogger streamFlowLogger;

    @Autowired
    private AgentProperties agentProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 非流式方法 ====================
    @Async
    public CompletableFuture<ChatResponse> run(String userMessage, String conversationId, String apiKey) {
        String activeConversationId = normalizeConversationId(conversationId);
        String turnId = UUID.randomUUID().toString();

        log.info("Agent 循环开始，conversationId: {}, turnId: {}, 用户消息: {}",
            activeConversationId, turnId, userMessage);

        List<Message> oldMessages = sessionStore.loadMessages(activeConversationId);
        String agentMemory = agentMemoryService.loadMemory();
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new Message("user", List.of(new Message.TextBlock(userMessage))));
        log.info("非流式记忆加载完成，conversationId: {}, oldMessagesCount: {}, newMessagesCount: {}, agentMemoryChars: {}, oldMessages: {}",
            activeConversationId, oldMessages.size(), newMessages.size(), agentMemory.length(), describeMessages(oldMessages));
        log.info("本轮初始消息，conversationId: {}, newMessages: {}",
            activeConversationId, describeMessages(newMessages));

        List<Map<String, Object>> toolDefs = toolRegistry.getToolDefinitions();

        int totalOutputTokens = 0;
        int maxToolRounds = agentConfig.maxToolRounds();
        for (int round = 0; round < maxToolRounds; round++) {
            log.info("Agent 第 {} 轮", round + 1);
            try {
                List<Message> apiHistory = new ArrayList<>(oldMessages);
                apiHistory.addAll(newMessages);
                log.info("准备调用 API，conversationId: {}, round: {}, oldMessagesCount: {}, newMessagesCount: {}, apiHistoryCount: {}, apiHistory: {}",
                    activeConversationId, round + 1, oldMessages.size(), newMessages.size(), apiHistory.size(), describeMessages(apiHistory));

                AnthropicClient.AnthropicResponse response =
                    anthropicClient.chat(apiHistory, toolDefs, agentMemory, 4096, apiKey);
                totalOutputTokens += response.outputTokens() != null ? response.outputTokens() : 0;
                sessionStore.updateUsage(activeConversationId, response.inputTokens(), response.outputTokens());

                if (response.toolCalls().isEmpty()) {
                    String reply = response.text() == null ? "" : response.text();
                    log.info("Agent 第 {} 轮完成，无工具调用，文本长度: {}", round + 1, reply.length());
                    newMessages.add(new Message("assistant", List.of(new Message.TextBlock(reply))));
                    log.info("准备批量写库，conversationId: {}, turnId: {}, newMessagesCount: {}, totalOutputTokens: {}, newMessages: {}",
                        activeConversationId, turnId, newMessages.size(), totalOutputTokens, describeMessages(newMessages));
                    sessionStore.appendTurn(activeConversationId, turnId, newMessages, totalOutputTokens, buildCompressor(apiKey));
                    return CompletableFuture.completedFuture(new ChatResponse(activeConversationId, reply));
                }

                log.info("Agent 第 {} 轮需执行 {} 个工具", round + 1, response.toolCalls().size());
                List<Message.ContentBlock> assistantBlocks = new ArrayList<>();
                if (response.text() != null && !response.text().isEmpty()) {
                    assistantBlocks.add(new Message.TextBlock(response.text()));
                }
                for (ToolCall tc : response.toolCalls()) {
                    assistantBlocks.add(new Message.ToolUseBlock(tc.id(), tc.name(), tc.input()));
                }
                newMessages.add(new Message("assistant", assistantBlocks));
                log.info("工具调用消息已加入本轮 newMessages，conversationId: {}, round: {}, newMessages: {}",
                    activeConversationId, round + 1, describeMessages(newMessages));

                List<Message.ContentBlock> resultBlocks = new ArrayList<>();
                for (ToolCall tc : response.toolCalls()) {
                    log.info("执行工具: {}, 参数: {}", tc.name(), tc.input());
                    ToolResult result = toolRegistry.execute(tc.name(), tc.input());
                    if (result.isError()) {
                        log.warn("工具 {} 执行失败: {}", tc.name(), result.content());
                    }
                    resultBlocks.add(new Message.ToolResultBlock(tc.id(),
                        truncateToolResult(result.content(), result.isError()), result.isError()));
                }
                newMessages.add(new Message("user", resultBlocks));
                log.info("工具结果消息已加入本轮 newMessages，conversationId: {}, round: {}, newMessages: {}",
                    activeConversationId, round + 1, describeMessages(newMessages));
            } catch (Exception e) {
                log.warn("Agent 非流式本轮失败，不写入数据库，conversationId: {}, turnId: {}, newMessages: {}",
                    activeConversationId, turnId, describeMessages(newMessages), e);
                String reply = "API 调用失败: " + e.getMessage();
                return CompletableFuture.completedFuture(new ChatResponse(activeConversationId, reply));
            }
        }

        log.warn("达到最大工具调用轮数，不写入数据库，conversationId: {}, turnId: {}, maxToolRounds: {}, newMessages: {}",
            activeConversationId, turnId, maxToolRounds, describeMessages(newMessages));
        String reply = "错误: 达到最大工具调用轮数（" + maxToolRounds + " 轮）";
        return CompletableFuture.completedFuture(new ChatResponse(activeConversationId, reply));
    }

    // ==================== 流式方法 ====================
    @Async
    public void runStream(String userMessage, String conversationId, SseEmitter emitter, String apiKey) {
        String rawConversationId = conversationId;
        String activeConversationId = normalizeConversationId(conversationId);
        String turnId = UUID.randomUUID().toString();

        log.info("Agent 流式循环开始，conversationId: {}, turnId: {}, 用户消息: {}",
            activeConversationId, turnId, userMessage);
        streamFlowLogger.write(turnId, activeConversationId, "STREAM_START", debugData(
            "rawConversationId", rawConversationId,
            "activeConversationId", activeConversationId,
            "userMessageLength", userMessage == null ? 0 : userMessage.length(),
            "userMessagePreview", preview(userMessage, 120)
        ));

        List<Message> oldMessages = sessionStore.loadMessages(activeConversationId);
        String agentMemory = agentMemoryService.loadMemory();
        List<Message> newMessages = new ArrayList<>();
        newMessages.add(new Message("user", List.of(new Message.TextBlock(userMessage))));
        log.info("流式记忆加载完成，conversationId: {}, oldMessagesCount: {}, newMessagesCount: {}, agentMemoryChars: {}, oldMessages: {}",
            activeConversationId, oldMessages.size(), newMessages.size(), agentMemory.length(), describeMessages(oldMessages));
        log.info("流式本轮初始消息，conversationId: {}, newMessages: {}",
            activeConversationId, describeMessages(newMessages));
        streamFlowLogger.write(turnId, activeConversationId, "AFTER_LOAD_MESSAGES", debugData(
            "oldMessagesCount", oldMessages.size(),
            "newMessagesCount", newMessages.size(),
            "agentMemoryChars", agentMemory.length(),
            "oldMessages", describeMessages(oldMessages),
            "newMessages", describeMessages(newMessages)
        ));

        List<Map<String, Object>> toolDefs = toolRegistry.getToolDefinitions();

        int totalOutputTokens = 0;
        try {
            // 第一帧告诉前端本次流式请求实际使用的 conversationId。
            emitter.send(objectMapper.writeValueAsString(Map.of(
                "type", "conversation_id",
                "conversationId", activeConversationId
            )));
            streamFlowLogger.write(turnId, activeConversationId, "EMIT_CONVERSATION_ID", debugData(
                "activeConversationId", activeConversationId
            ));

            int maxToolRounds = agentConfig.maxToolRounds();
            for (int round = 0; round < maxToolRounds; round++) {
                log.info("Agent 流式第 {} 轮", round + 1);

                List<Message> apiHistory = new ArrayList<>(oldMessages);
                apiHistory.addAll(newMessages);
                log.info("准备调用流式 API，conversationId: {}, round: {}, oldMessagesCount: {}, newMessagesCount: {}, apiHistoryCount: {}, apiHistory: {}",
                    activeConversationId, round + 1, oldMessages.size(), newMessages.size(), apiHistory.size(), describeMessages(apiHistory));
                streamFlowLogger.write(turnId, activeConversationId, "API_HISTORY_READY", debugData(
                    "round", round + 1,
                    "oldMessagesCount", oldMessages.size(),
                    "newMessagesCount", newMessages.size(),
                    "apiHistoryCount", apiHistory.size(),
                    "toolDefsCount", toolDefs.size(),
                    "apiHistory", describeMessages(apiHistory)
                ));

                AnthropicClient.AnthropicResponse apiResp =
                    anthropicClient.chatStream(apiHistory, toolDefs, agentMemory, apiKey,
                        token -> {
                            try {
                                // 用 JSON 字符串包装：含真换行的 token 经 JSON 转义后变单行 ASCII+UTF-8，
                                // 不会被 SSE 协议拆行。前端 JSON.parse 还原。
                                emitter.send(objectMapper.writeValueAsString(token));
                            } catch (Exception e) {
                                throw new RuntimeException("客户端已断开连接", e);
                            }
                        });
                totalOutputTokens += apiResp.outputTokens() != null ? apiResp.outputTokens() : 0;
                streamFlowLogger.write(turnId, activeConversationId, "API_RESPONSE", debugData(
                    "round", round + 1,
                    "inputTokens", apiResp.inputTokens(),
                    "outputTokens", apiResp.outputTokens(),
                    "totalTokens", tokenTotal(apiResp.inputTokens(), apiResp.outputTokens()),
                    "toolCallsCount", apiResp.toolCalls().size(),
                    "textLength", apiResp.text() == null ? 0 : apiResp.text().length(),
                    "textPreview", preview(apiResp.text(), 160)
                ));
                sessionStore.updateUsage(activeConversationId, apiResp.inputTokens(), apiResp.outputTokens(), turnId);

                if (apiResp.toolCalls().isEmpty()) {
                    String reply = apiResp.text() == null ? "" : apiResp.text();
                    log.info("Agent 流式第 {} 轮完成，无工具调用，文本长度: {}", round + 1, reply.length());
                    newMessages.add(new Message("assistant", List.of(new Message.TextBlock(reply))));
                    log.info("准备写入流式本轮消息，conversationId: {}, turnId: {}, newMessagesCount: {}, totalOutputTokens: {}, newMessages: {}",
                        activeConversationId, turnId, newMessages.size(), totalOutputTokens, describeMessages(newMessages));
                    streamFlowLogger.write(turnId, activeConversationId, "FINAL_ASSISTANT_READY", debugData(
                        "round", round + 1,
                        "newMessagesCount", newMessages.size(),
                        "newMessages", describeMessages(newMessages)
                    ));
                    sessionStore.appendTurn(activeConversationId, turnId, newMessages, totalOutputTokens, buildCompressor(apiKey));
                    streamFlowLogger.write(turnId, activeConversationId, "APPEND_TURN_DONE", debugData(
                        "savedMessagesCount", newMessages.size(),
                        "replyLength", reply.length(),
                        "replyPreview", preview(reply, 160)
                    ));
                    emitter.complete();
                    streamFlowLogger.write(turnId, activeConversationId, "STREAM_COMPLETE", debugData(
                        "reason", "final assistant text"
                    ));
                    return;
                }

                log.info("Agent 流式第 {} 轮需执行 {} 个工具", round + 1, apiResp.toolCalls().size());
                List<Message.ContentBlock> assistantBlocks = new ArrayList<>();
                if (apiResp.text() != null && !apiResp.text().isEmpty()) {
                    assistantBlocks.add(new Message.TextBlock(apiResp.text()));
                }
                for (ToolCall tc : apiResp.toolCalls()) {
                    assistantBlocks.add(new Message.ToolUseBlock(tc.id(), tc.name(), tc.input()));
                }
                newMessages.add(new Message("assistant", assistantBlocks));
                log.info("流式工具调用消息已加入 newMessages，conversationId: {}, round: {}, newMessages: {}",
                    activeConversationId, round + 1, describeMessages(newMessages));
                streamFlowLogger.write(turnId, activeConversationId, "TOOL_USE_ADDED", debugData(
                    "round", round + 1,
                    "toolCallsCount", apiResp.toolCalls().size(),
                    "newMessagesCount", newMessages.size(),
                    "newMessages", describeMessages(newMessages)
                ));

                List<Message.ContentBlock> resultBlocks = new ArrayList<>();
                for (ToolCall tc : apiResp.toolCalls()) {
                    log.info("执行工具: {}, 参数: {}", tc.name(), tc.input());
                    ToolResult result = toolRegistry.execute(tc.name(), tc.input());
                    if (result.isError()) {
                        log.warn("工具 {} 执行失败: {}", tc.name(), result.content());
                    }
                    resultBlocks.add(new Message.ToolResultBlock(tc.id(),
                        truncateToolResult(result.content(), result.isError()), result.isError()));
                }
                newMessages.add(new Message("user", resultBlocks));
                log.info("流式工具结果消息已加入 newMessages，conversationId: {}, round: {}, newMessages: {}",
                    activeConversationId, round + 1, describeMessages(newMessages));
                streamFlowLogger.write(turnId, activeConversationId, "TOOL_RESULT_ADDED", debugData(
                    "round", round + 1,
                    "newMessagesCount", newMessages.size(),
                    "newMessages", describeMessages(newMessages)
                ));
            }

            log.warn("流式达到最大工具调用轮数，不写入数据库，conversationId: {}, turnId: {}, maxToolRounds: {}, newMessages: {}",
                activeConversationId, turnId, maxToolRounds, describeMessages(newMessages));
            streamFlowLogger.write(turnId, activeConversationId, "MAX_TOOL_ROUNDS", debugData(
                "maxToolRounds", maxToolRounds,
                "newMessagesCount", newMessages.size(),
                "newMessages", describeMessages(newMessages)
            ));
            emitter.send(objectMapper.writeValueAsString("错误: 达到最大工具调用轮数（" + maxToolRounds + " 轮）"));
            emitter.complete();

        } catch (Exception e) {
            log.warn("Agent 流式本轮失败，不写入数据库，conversationId: {}, turnId: {}, newMessages: {}",
                activeConversationId, turnId, describeMessages(newMessages), e);
            streamFlowLogger.write(turnId, activeConversationId, "STREAM_ERROR", debugData(
                "errorClass", e.getClass().getName(),
                "errorMessage", e.getMessage(),
                "newMessagesCount", newMessages.size(),
                "newMessages", describeMessages(newMessages)
            ));
            emitter.completeWithError(e);
        }
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }

    private String describeMessages(List<Message> messages) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            parts.add("#" + i + " " + message.role() + " " + describeBlocks(message.contentBlocks()));
        }
        return parts.toString();
    }

    private String describeBlocks(List<Message.ContentBlock> blocks) {
        List<String> parts = new ArrayList<>();
        for (Message.ContentBlock block : blocks) {
            if (block instanceof Message.TextBlock textBlock) {
                parts.add("text=\"" + preview(textBlock.text(), 80) + "\"");
            } else if (block instanceof Message.ToolUseBlock toolUseBlock) {
                parts.add("tool_use id=" + toolUseBlock.id()
                    + ", name=" + toolUseBlock.name()
                    + ", input=" + preview(String.valueOf(toolUseBlock.input()), 80));
            } else if (block instanceof Message.ToolResultBlock toolResultBlock) {
                parts.add("tool_result id=" + toolResultBlock.toolUseId()
                    + ", error=" + toolResultBlock.isError()
                    + ", content=\"" + preview(toolResultBlock.content(), 80) + "\"");
            }
        }
        return parts.toString();
    }

    private String preview(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace("\r", "\\r").replace("\n", "\\n");
        if (oneLine.length() <= maxLength) {
            return oneLine;
        }
        return oneLine.substring(0, maxLength) + "...";
    }

    private Object tokenTotal(Integer inputTokens, Integer outputTokens) {
        if (inputTokens == null || outputTokens == null) {
            return "null";
        }
        return inputTokens + outputTokens;
    }

    /**
     * 截断过长的工具结果，防止大文件内容撑爆上下文。
     * 错误信息不截断（需要完整错误栈来排查问题）。
     */
    private String truncateToolResult(String content, boolean isError) {
        if (content == null) {
            return "";
        }
        int maxChars = agentProperties.maxToolResultChars();
        if (maxChars <= 0 || content.length() <= maxChars || isError) {
            return content;
        }
        String truncated = content.substring(0, maxChars);
        return truncated + "\n\n[... 工具输出过长，已截断。原始长度: "
            + content.length() + " 字符，显示了前 " + maxChars + " 字符。"
            + " 如需查看完整内容，请用 rg 搜索关键词定位到具体部分。]";
    }

    private Map<String, Object> debugData(Object... values) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            data.put(String.valueOf(values[i]), values[i + 1] == null ? "null" : values[i + 1]);
        }
        return data;
    }

    /**
     * 构造记忆压缩器。
     *
     * SessionStore 不直接依赖 AnthropicClient，通过这个 lambda 把 LLM 能力注入。
     * 压缩时把老消息拼成文本，加一条"请总结"指令发给 LLM，不带工具，max_tokens=512。
     */
    private Compressor buildCompressor(String apiKey) {
        return oldMessages -> {
            // 把老消息序列化成文本
            StringBuilder sb = new StringBuilder();
            for (Message msg : oldMessages) {
                sb.append("[").append(msg.role()).append("]: ");
                for (Message.ContentBlock block : msg.contentBlocks()) {
                    if (block instanceof Message.TextBlock tb) {
                        sb.append(tb.text()).append("\n");
                    } else if (block instanceof Message.ToolUseBlock tu) {
                        sb.append("[调用工具 ").append(tu.name())
                            .append(": ").append(tu.input()).append("]\n");
                    } else if (block instanceof Message.ToolResultBlock tr) {
                        String preview = tr.content() != null && tr.content().length() > 200
                            ? tr.content().substring(0, 200) + "..."
                            : tr.content();
                        sb.append("[工具结果: ").append(preview).append("]\n");
                    }
                }
            }

            List<Message> summaryRequest = List.of(
                new Message("user", List.of(new Message.TextBlock(
                    "请用一段话总结以下对话内容，保留所有关键事实、工具调用结果、用户偏好。最多500字。\n\n" + sb)))
            );

            // 调 LLM，max_tokens=512 限制输出长度，不带工具
            AnthropicClient.AnthropicResponse resp = anthropicClient.chat(
                summaryRequest, List.of(), "", 512, apiKey);
            return resp.text();
        };
    }
}
