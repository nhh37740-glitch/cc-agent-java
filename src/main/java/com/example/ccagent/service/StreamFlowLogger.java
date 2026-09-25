package com.example.ccagent.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GET 流式请求的单独调试日志。
 *
 * 普通 SLF4J 日志会和其他日志混在一起。这个类只写 logs/get-stream-flow.log，
 * 用来追踪一次 GET /api/chat/stream 的会话 ID、历史消息、API 输入和 token 用量。
 */
@Service
public class StreamFlowLogger {

    private static final Logger log = LoggerFactory.getLogger(StreamFlowLogger.class);

    private static final Path LOG_FILE = Path.of("logs", "get-stream-flow.log");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public synchronized void write(String turnId, String conversationId, String step, Map<String, Object> data) {
        try {
            Files.createDirectories(LOG_FILE.getParent());

            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", Instant.now().toString());
            line.put("turnId", turnId);
            line.put("conversationId", conversationId);
            line.put("step", step);
            if (data != null) {
                line.putAll(data);
            }

            Files.writeString(
                LOG_FILE,
                objectMapper.writeValueAsString(line) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (Exception e) {
            log.warn("写入 GET 流式调试日志失败", e);
        }
    }
}
