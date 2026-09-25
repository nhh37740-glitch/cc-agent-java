package com.example.ccagent.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.example.ccagent.config.AgentProperties;

/**
 * AGENT.MD 长期记忆读取服务。
 *
 * 这个类只读取 workspace/memory/AGENT.MD。
 * 是否创建和更新 AGENT.MD，由模型自己决定是否调用 file_write。
 */
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    @Autowired
    private AgentProperties properties;

    /**
     * 读取 AGENT.MD。
     *
     * 文件不存在或读取失败时返回空字符串，不创建文件。
     */
    public String loadMemory() {
        Path path = memoryFile();
        if (!Files.exists(path)) {
            log.info("AGENT.MD 未读取到内容，path: {}", path);
            return "";
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            log.info("AGENT.MD 读取完成，path: {}, chars: {}, bytes: {}",
                path, content.length(), content.getBytes(StandardCharsets.UTF_8).length);
            return content;
        } catch (Exception e) {
            log.warn("AGENT.MD 读取失败，本轮按空内容处理，path: {}", path, e);
            return "";
        }
    }

    private Path memoryFile() {
        String workspaceDir = properties.workspaceDir() == null || properties.workspaceDir().isBlank()
            ? "./workspace"
            : properties.workspaceDir();
        return Path.of(workspaceDir).toAbsolutePath().normalize()
            .resolve("memory")
            .resolve("AGENT.MD");
    }
}
