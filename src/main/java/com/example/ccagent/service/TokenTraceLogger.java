package com.example.ccagent.service;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token 计数专用追踪日志。
 *
 * 独立写到一个文件，记录 runningTotal 的每次读写变化，
 * 用于排查计数异常（归零、不增等）。
 */
@Component
public class TokenTraceLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TokenTraceLogger.class);

    private final PrintWriter writer;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public TokenTraceLogger() {
        PrintWriter w;
        try {
            Path file = Path.of("logs", "token-trace.log");
            Files.createDirectories(file.getParent());
            w = new PrintWriter(new FileWriter(file.toFile(), StandardCharsets.UTF_8, true), true);
            w.println("=== TokenTrace 启动 " + LocalDateTime.now().format(FMT) + " ===");
            w.flush();
        } catch (IOException e) {
            log.warn("无法创建 token-trace.log", e);
            w = null;
        }
        this.writer = w;
    }

    public void record(String conversationId, String step, int runningTotalBefore,
                       int runningTotalAfter, String detail) {
        if (writer == null) {
            return;
        }
        String line = String.format("%s | %s | %s | before=%d after=%d | %s",
            LocalDateTime.now().format(FMT), conversationId, step,
            runningTotalBefore, runningTotalAfter, detail);
        writer.println(line);
        writer.flush();
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.println("=== TokenTrace 停止 " + LocalDateTime.now().format(FMT) + " ===");
            writer.close();
        }
    }
}
