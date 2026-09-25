package com.example.ccagent.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.ccagent.config.AgentProperties;
import com.example.ccagent.model.Compressor;
import com.example.ccagent.model.ConversationSummary;
import com.example.ccagent.model.JsonlBackupEntity;
import com.example.ccagent.model.Message;
import com.example.ccagent.model.SessionJson;
import com.example.ccagent.repository.JsonlBackupRepository;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 会话存储服务。
 *
 * 每个会话一个 JSON 文件，runningTotalTokens 是顶层字段，直接读写。
 * 压缩时老消息移到 .archive.json，活跃 JSON 只保留摘要 + 本轮消息。
 */
@Service
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final Pattern SAFE_CONVERSATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    @Autowired
    private JsonlBackupRepository jsonlBackupRepository;

    @Autowired
    private AgentProperties properties;

    @Autowired
    private StreamFlowLogger streamFlowLogger;

    @Autowired
    private TokenTraceLogger trace;

    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    /** 按会话加锁，防止同一会话的并发读写互相覆盖。 */
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    private Object lockFor(String conversationId) {
        return sessionLocks.computeIfAbsent(conversationId, k -> new Object());
    }

    // ==================== 会话列表 ====================

    /**
     * 读取全部会话，最近更新的排在前面。
     */
    public List<ConversationSummary> listConversations() {
        Path dir = sessionsDir();
        if (!Files.exists(dir)) {
            log.info("会话目录不存在，返回空列表，dir: {}", dir);
            return List.of();
        }

        try (Stream<Path> paths = Files.list(dir)) {
            List<Path> sessionFiles = paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".json"))
                .filter(path -> !path.getFileName().toString().endsWith(".archive.json"))
                .toList();
            log.info("扫描会话 JSON 目录，dir: {}, fileCount: {}", dir, sessionFiles.size());

            List<ConversationSummary> summaries = sessionFiles.stream()
                .map(this::toConversationSummary)
                .sorted(Comparator.comparing(ConversationSummary::updatedAt).reversed())
                .toList();

            log.info("会话列表扫描完成，dir: {}, conversationCount: {}", dir, summaries.size());
            return summaries;
        } catch (Exception e) {
            throw new IllegalStateException("扫描会话目录失败，dir=" + dir, e);
        }
    }

    // ==================== 读取消息 ====================

    /**
     * 读取某个会话的全部活跃消息（不包含已归档的老消息）。
     */
    public List<Message> loadMessages(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            log.info("读取消息跳过：conversationId 为空");
            return List.of();
        }

        SessionJson json = readSessionJson(conversationId);
        if (json == null) {
            log.info("会话 JSON 不存在，conversationId: {}", conversationId);
            return List.of();
        }

        log.info("消息加载完成，conversationId: {}, messageCount: {}",
            conversationId, json.messages().size());
        return json.messages();
    }

    // ==================== 保存本轮消息（含压缩检查） ====================

    /**
     * 保存一轮对话消息，内部检查 runningTotalTokens 是否超阈值并触发压缩。
     *
     * @param conversationId 会话 ID
     * @param turnId 本轮 ID（保留参数兼容性，当前 JSON 格式不再按 turnId 切分）
     * @param messages 本轮新增的消息
     * @param totalOutputTokens 本轮所有 API 调用 outputTokens 之和
     * @param compressor 压缩器（AgentService 传入的 lambda）
     */
    @Transactional
    public void appendTurn(String conversationId, String turnId,
                           List<Message> messages, int totalOutputTokens,
                           Compressor compressor) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (messages == null || messages.isEmpty()) {
            return;
        }

        synchronized (lockFor(conversationId)) {
            doAppendTurnLocked(conversationId, turnId, messages, totalOutputTokens, compressor);
        }
    }

    private void doAppendTurnLocked(String conversationId, String turnId,
                                    List<Message> messages, int totalOutputTokens,
                                    Compressor compressor) {
        SessionJson json = readSessionJson(conversationId);
        int runningTotal = json == null ? 0 : json.runningTotalTokens();
        List<Message> existingMessages = json == null ? List.of() : json.messages();
        int newRunningTotal = runningTotal + totalOutputTokens;
        int threshold = properties.compressionThresholdTokens();

        trace.record(conversationId, "appendTurn_entry", runningTotal, newRunningTotal,
            "totalOutputTokens=" + totalOutputTokens + " existingCount=" + existingMessages.size()
            + " threshold=" + threshold + " newCount=" + messages.size());

        log.info("appendTurn，conversationId: {}, runningTotal: {}, newRunningTotal: {}, threshold: {}, existingCount: {}, newCount: {}",
            conversationId, runningTotal, newRunningTotal, threshold,
            existingMessages.size(), messages.size());

        if (newRunningTotal > threshold && !existingMessages.isEmpty()) {
            // 超阈值：压缩老消息
            try {
                String summary = compressor.compress(existingMessages);
                if (summary == null || summary.isBlank()) {
                    log.warn("摘要为空，跳过压缩，正常追加，conversationId: {}", conversationId);
                    doNormalAppend(conversationId, json, existingMessages, messages, newRunningTotal, totalOutputTokens);
                    return;
                }

                // 归档老消息
                archiveMessages(conversationId, existingMessages);

                // 构造摘要消息
                Message summaryMsg = new Message("assistant", List.of(
                    new Message.TextBlock("[历史对话摘要] " + summary)));

                // 重写活跃 JSON：[摘要] + [本轮消息]
                List<Message> newMessages = new ArrayList<>();
                newMessages.add(summaryMsg);
                newMessages.addAll(messages);
                int summaryTokens = (int) (summary.length() * 0.6);
                int newRunningTotalAfterCompress = summaryTokens + totalOutputTokens;

                String title = json == null ? firstUserText(messages) : json.title();
                Instant createdAt = json == null ? Instant.now() : json.createdAt();
                SessionJson newJson = new SessionJson(
                    conversationId, title,
                    newRunningTotalAfterCompress,
                    properties.contextWindowLimitTokens(),
                    json == null ? 0 : json.lastInputTokens(),
                    totalOutputTokens,
                    createdAt, Instant.now(),
                    newMessages
                );
                writeSessionJson(newJson);
                rewriteBackup(conversationId, newMessages);
                trace.record(conversationId, "compress", runningTotal, newRunningTotalAfterCompress,
                    "summaryChars=" + summary.length() + " summaryTokens=" + summaryTokens
                    + " outputTokens=" + totalOutputTokens
                    + " archivedCount=" + existingMessages.size()
                    + " newCount=" + messages.size());
                log.info("记忆压缩完成，conversationId: {}, 老消息 {} 条归档 → 1 条摘要，本轮 {} 条保留，runningTotal: {} → {}",
                    conversationId, existingMessages.size(), messages.size(),
                    runningTotal, newRunningTotalAfterCompress);
            } catch (Exception e) {
                log.warn("记忆压缩失败，跳过压缩，正常追加，conversationId: {}", conversationId, e);
                doNormalAppend(conversationId, json, existingMessages, messages, newRunningTotal, totalOutputTokens);
            }
        } else {
            // 未超阈值：正常追加
            doNormalAppend(conversationId, json, existingMessages, messages, newRunningTotal, totalOutputTokens);
        }
    }

    /** 正常追加：消息拼到已有消息后面，更新 runningTotalTokens。 */
    private void doNormalAppend(String conversationId, SessionJson json,
                                List<Message> existingMessages, List<Message> newMessages,
                                int newRunningTotal, int totalOutputTokens) {
        int runningTotalBefore = json == null ? 0 : json.runningTotalTokens();
        List<Message> allMessages = new ArrayList<>(existingMessages);
        allMessages.addAll(newMessages);

        String title;
        Instant createdAt;
        int lastInput = 0;
        int lastOutput = 0;
        if (json == null) {
            title = firstUserText(newMessages);
            createdAt = Instant.now();
        } else {
            title = json.title();
            createdAt = json.createdAt();
            lastInput = json.lastInputTokens();
            lastOutput = json.lastOutputTokens();
        }

        SessionJson newJson = new SessionJson(
            conversationId, title,
            newRunningTotal,
            properties.contextWindowLimitTokens(),
            lastInput, lastOutput,
            createdAt, Instant.now(),
            allMessages
        );
        writeSessionJson(newJson);
        appendBackup(conversationId, newMessages);
        trace.record(conversationId, "appendTurn", runningTotalBefore, newRunningTotal,
            "totalOutputTokens=" + totalOutputTokens + " existingCount=" + existingMessages.size() + " newCount=" + newMessages.size());
        log.info("本轮消息追加完成，conversationId: {}, totalMessages: {}, runningTotal: {}",
            conversationId, allMessages.size(), newRunningTotal);
    }

    // ==================== Token 用量记录 ====================

    /**
     * 记录最近一次 API 调用的 token 用量。
     * 更新 JSON 的 lastInputTokens / lastOutputTokens，前端会话列表展示用。
     */
    @Transactional
    public void updateUsage(String conversationId, Integer inputTokens, Integer outputTokens) {
        updateUsageInternal(conversationId, inputTokens, outputTokens, null);
    }

    @Transactional
    public void updateUsage(String conversationId, Integer inputTokens, Integer outputTokens, String debugTurnId) {
        updateUsageInternal(conversationId, inputTokens, outputTokens, debugTurnId);
    }

    private void updateUsageInternal(String conversationId, Integer inputTokens, Integer outputTokens, String debugTurnId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        if (inputTokens == null || outputTokens == null) {
            log.info("API usage 不完整，跳过写入，conversationId: {}", conversationId);
            return;
        }

        synchronized (lockFor(conversationId)) {
            SessionJson json = readSessionJson(conversationId);
            if (json == null) {
                log.info("updateUsage 跳过：会话 JSON 不存在，conversationId: {}", conversationId);
                return;
            }

        // runningTotalTokens 取 max(已有值, inputTokens)。
        // inputTokens 是 API 计算的真实上下文大小（含 system prompt、工具定义），
        // 我们的累加值可能偏低，用较大值校准。缓存命中时 inputTokens 会偏小，
        // 但 Math.max 只在 inputTokens 更大时才更新，缓存命中的小值不影响。
        int before = json.runningTotalTokens();
        int calibratedTotal = Math.max(before, inputTokens);

        SessionJson updated = new SessionJson(
            json.conversationId(), json.title(),
            calibratedTotal,
            json.contextWindowLimitTokens(),
            inputTokens, outputTokens,
            json.createdAt(), Instant.now(),
            json.messages()
        );
        writeSessionJson(updated);
        trace.record(conversationId, "updateUsage", before, calibratedTotal,
            "inputTokens=" + inputTokens + " outputTokens=" + outputTokens
            + (calibratedTotal > before ? " CALIBRATED" : ""));
        log.info("token 用量更新，conversationId: {}, inputTokens: {}, outputTokens: {}",
            conversationId, inputTokens, outputTokens);
        } // synchronized
    }

    // ==================== 清空会话 ====================

    @Transactional
    public void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        try {
            Path sessionFile = sessionFile(conversationId);
            Files.deleteIfExists(sessionFile);
            Path archiveFile = archiveFile(conversationId);
            Files.deleteIfExists(archiveFile);
            log.info("会话文件已删除，conversationId: {}", conversationId);
        } catch (Exception e) {
            throw new IllegalStateException("删除会话文件失败，conversationId=" + conversationId, e);
        }
        jsonlBackupRepository.deleteByConversationId(conversationId);
        log.info("会话备份行已删除，conversationId: {}", conversationId);
    }

    // ==================== JSON 文件读写 ====================

    private SessionJson readSessionJson(String conversationId) {
        Path file = sessionFile(conversationId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return objectMapper.readValue(content, SessionJson.class);
        } catch (Exception e) {
            throw new IllegalStateException("读取会话 JSON 失败，conversationId=" + conversationId + ", path=" + file, e);
        }
    }

    private void writeSessionJson(SessionJson json) {
        Path file = sessionFile(json.conversationId());
        try {
            Files.createDirectories(file.getParent());
            String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("写入会话 JSON 失败，conversationId=" + json.conversationId() + ", path=" + file, e);
        }
    }

    // ==================== 归档 ====================

    private void archiveMessages(String conversationId, List<Message> oldMessages) {
        Path archiveFile = archiveFile(conversationId);
        try {
            Files.createDirectories(archiveFile.getParent());
            // 读取已有归档（如果存在），追加老消息，写回
            List<Message> existingArchive = List.of();
            if (Files.exists(archiveFile)) {
                try {
                    existingArchive = objectMapper.readValue(
                        Files.readString(archiveFile, StandardCharsets.UTF_8),
                        objectMapper.getTypeFactory().constructCollectionType(List.class, Message.class));
                } catch (Exception e) {
                    log.warn("读取归档文件失败，将覆盖，path: {}", archiveFile, e);
                }
            }
            List<Message> allArchive = new ArrayList<>(existingArchive);
            allArchive.addAll(oldMessages);
            String content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(allArchive);
            Files.writeString(archiveFile, content, StandardCharsets.UTF_8);
            log.info("老消息归档完成，conversationId: {}, 本轮归档: {} 条，归档总计: {} 条，path: {}",
                conversationId, oldMessages.size(), allArchive.size(), archiveFile);
        } catch (Exception e) {
            throw new IllegalStateException("写入归档文件失败，conversationId=" + conversationId, e);
        }
    }

    // ==================== SQLite 备份 ====================

    private void appendBackup(String conversationId, List<Message> messages) {
        try {
            long existingCount = jsonlBackupRepository.countByConversationId(conversationId);
            List<JsonlBackupEntity> backups = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                JsonlBackupEntity backup = new JsonlBackupEntity();
                backup.setConversationId(conversationId);
                backup.setSeq(existingCount + i + 1);
                backup.setLineText(serializeMessageForBackup(messages.get(i)));
                backup.setCreatedAt(Instant.now());
                backups.add(backup);
            }
            jsonlBackupRepository.saveAll(backups);
        } catch (Exception e) {
            log.warn("SQLite 备份追加失败，conversationId: {}, count: {}", conversationId, messages.size(), e);
        }
    }

    private void rewriteBackup(String conversationId, List<Message> messages) {
        try {
            jsonlBackupRepository.deleteByConversationId(conversationId);
            List<JsonlBackupEntity> backups = new ArrayList<>();
            for (int i = 0; i < messages.size(); i++) {
                JsonlBackupEntity backup = new JsonlBackupEntity();
                backup.setConversationId(conversationId);
                backup.setSeq(i + 1L);
                backup.setLineText(serializeMessageForBackup(messages.get(i)));
                backup.setCreatedAt(Instant.now());
                backups.add(backup);
            }
            jsonlBackupRepository.saveAll(backups);
        } catch (Exception e) {
            log.warn("SQLite 备份重写失败，conversationId: {}, count: {}", conversationId, messages.size(), e);
        }
    }

    private String serializeMessageForBackup(Message message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception e) {
            return "{\"error\": \"序列化失败: " + e.getMessage() + "\"}";
        }
    }

    // ==================== 工具方法 ====================

    private ConversationSummary toConversationSummary(Path sessionFile) {
        try {
            String content = Files.readString(sessionFile, StandardCharsets.UTF_8);
            SessionJson json = objectMapper.readValue(content, SessionJson.class);
            return new ConversationSummary(
                json.conversationId(),
                json.title(),
                json.updatedAt(),
                json.contextWindowLimitTokens(),
                json.lastInputTokens(),
                json.lastOutputTokens(),
                json.lastInputTokens() + json.lastOutputTokens(),
                json.runningTotalTokens()
            );
        } catch (Exception e) {
            String fileName = sessionFile.getFileName().toString();
            String conversationId = fileName.substring(0, fileName.length() - ".json".length());
            log.warn("解析会话 JSON 失败，返回占位摘要，conversationId: {}, path: {}", conversationId, sessionFile, e);
            return new ConversationSummary(
                conversationId, "（无法读取）", Instant.EPOCH,
                0, 0, 0, 0, 0
            );
        }
    }

    private String firstUserText(List<Message> messages) {
        for (Message msg : messages) {
            if (!"user".equals(msg.role())) {
                continue;
            }
            for (Message.ContentBlock block : msg.contentBlocks()) {
                if (block instanceof Message.TextBlock textBlock) {
                    String text = textBlock.text();
                    if (text != null && !text.isBlank()) {
                        return text.length() > 40 ? text.substring(0, 40) : text;
                    }
                }
            }
        }
        return "未命名会话";
    }

    // ==================== 文件路径 ====================

    private Path sessionFile(String conversationId) {
        validateConversationId(conversationId);
        return sessionsDir().resolve(conversationId + ".json").normalize();
    }

    private Path archiveFile(String conversationId) {
        validateConversationId(conversationId);
        return sessionsDir().resolve(conversationId + ".archive.json").normalize();
    }

    private Path sessionsDir() {
        String workspaceDir = properties.workspaceDir() == null || properties.workspaceDir().isBlank()
            ? "./workspace"
            : properties.workspaceDir();
        return Path.of(workspaceDir).toAbsolutePath().normalize().resolve("data").resolve("sessions");
    }

    private void validateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId 不能为空");
        }
        if (!SAFE_CONVERSATION_ID.matcher(conversationId).matches()) {
            throw new IllegalArgumentException("conversationId 只能包含字母、数字、点、下划线和横杠，长度 1 到 100");
        }
    }
}
