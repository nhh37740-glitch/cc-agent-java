package com.example.ccagent.model;

import java.time.Instant;
import java.util.List;

/**
 * 会话 JSON 文件的完整结构。
 *
 * 每个会话对应一个 {conversationId}.json 文件，内容就是这个 record 的 Jackson 序列化结果。
 * runningTotalTokens 是顶层字段，appendTurn 时直接读写，不需要像 JSONL 那样扫描行。
 */
public record SessionJson(
    String conversationId,
    String title,
    int runningTotalTokens,
    int contextWindowLimitTokens,
    int lastInputTokens,
    int lastOutputTokens,
    Instant createdAt,
    Instant updatedAt,
    List<Message> messages
) {}
