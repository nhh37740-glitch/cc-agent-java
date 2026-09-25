package com.example.ccagent.model;

import java.time.Instant;

/**
 * 会话列表里显示的一项。
 *
 * 这些字段从 workspace/data/sessions/*.jsonl 扫描出来。
 * 前端只需要这些轻量字段，不需要知道 JSONL 文件的内部细节。
 */
public record ConversationSummary(
    String conversationId,
    String title,
    Instant updatedAt,
    Integer contextWindowLimitTokens,
    Integer currentInputTokens,
    Integer currentOutputTokens,
    Integer currentTotalTokens,
    Integer currentWindowTokens
) {}
