// package = 声明这个文件属于哪个包
package com.example.ccagent.controller;

// import = 引入其他包的类，没有这行编译器找不到这些类
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
// SseEmitter = Spring MVC 提供的 SSE 工具类，用于向客户端推送流式数据
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.example.ccagent.model.ChatRequest;
import com.example.ccagent.model.ChatResponse;
import com.example.ccagent.model.ConversationSummary;
import com.example.ccagent.model.Message;
import com.example.ccagent.service.AgentService;
import com.example.ccagent.service.DeepSeekKeyService;
import jakarta.servlet.http.HttpServletRequest;
import com.example.ccagent.service.SessionStore;
import com.example.ccagent.service.StreamFlowLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// import = 引入日志
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChatController — 接收用户请求，返回响应
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AgentService agentService;

    @Autowired
    private DeepSeekKeyService deepSeekKeys;

    @Autowired
    private SessionStore sessionStore;

    @Autowired
    private StreamFlowLogger streamFlowLogger;

    // ==================== 非流式端点 ====================
    @PostMapping("/chat")
    public CompletableFuture<ChatResponse> chat(@RequestBody ChatRequest request, HttpServletRequest httpRequest) {
        String userMessage = request.message();
        String conversationId = request.conversationId();
        log.info("收到 POST /api/chat 请求，conversationId: {}, 消息: {}", conversationId, userMessage);
        return agentService.run(userMessage, conversationId, deepSeekKeys.resolve(httpRequest));
    }

    // ==================== 流式端点（SSE） ====================
    // produces 显式声明 charset=UTF-8，让 Tomcat 把响应字节按 UTF-8 写出。
    // 不加这个参数时 Spring 默认 Content-Type 是 "text/event-stream" 无 charset，
    // Tomcat 会用 ISO-8859-1 输出，中文字节被错误截断成 ? 或乱码。
    @GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(
            @RequestParam String message,
            @RequestParam(required = false) String conversationId,
            HttpServletRequest httpRequest) {
        log.info("收到 GET /api/chat/stream 请求，conversationId: {}, 消息: {}", conversationId, message);
        streamFlowLogger.write("controller", conversationId, "GET_STREAM_REQUEST", Map.of(
            "rawConversationId", conversationId == null ? "" : conversationId,
            "messageLength", message == null ? 0 : message.length(),
            "messagePreview", preview(message, 120)
        ));
        SseEmitter emitter = new SseEmitter(300000L);
        agentService.runStream(message, conversationId, emitter, deepSeekKeys.resolve(httpRequest));
        return emitter;
    }

    // ==================== 会话调试端点 ====================
    @GetMapping("/conversations")
    public List<ConversationSummary> listConversations() {
        log.info("收到 GET /api/conversations 请求");
        return sessionStore.listConversations();
    }

    @GetMapping("/conversations/{id}")
    public List<Message> getConversation(@PathVariable String id) {
        log.info("收到 GET /api/conversations/{} 请求", id);
        return sessionStore.loadMessages(id);
    }

    @DeleteMapping("/conversations/{id}")
    public void deleteConversation(@PathVariable String id) {
        log.info("收到 DELETE /api/conversations/{} 请求", id);
        sessionStore.clear(id);
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
}
