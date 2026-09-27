package com.example.ccagent.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 对话消息。
 *
 * Anthropic 风格的消息只有两层：
 *   1. Message：role + contentBlocks
 *   2. ContentBlock：text、tool_use、tool_result
 *
 * role 只有 "user" 和 "assistant"。
 * contentBlocks 是内容块列表，同一条消息里可以同时有文本和工具调用。
 */
public record Message(String role, List<ContentBlock> contentBlocks) {

    /**
     * Message.contentBlocks 里的一个内容块。
     *
     * Jackson 序列化时根据 "type" 字段区分具体子类型。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ToolUseBlock.class, name = "tool_use"),
        @JsonSubTypes.Type(value = ToolResultBlock.class, name = "tool_result")
    })
    public interface ContentBlock {}

    /**
     * 文本内容块。
     *
     * user 消息和 assistant 消息都可以包含 TextBlock。
     */
    @JsonTypeName("text")
    public record TextBlock(String text) implements ContentBlock {}

    /**
     * assistant 发出的工具调用命令。
     *
     * 这个 block 会转成 Anthropic API 的 tool_use。
     */
    @JsonTypeName("tool_use")
    public record ToolUseBlock(
        String id,
        String name,
        Map<String, Object> input
    ) implements ContentBlock {}

    /**
     * user 返回的工具执行结果。
     *
     * toolUseId 必须等于前一条 assistant tool_use block 的 id。
     * JSON 字段名是 tool_use_id 和 is_error，和 Java 驼峰命名不一致，需要 @JsonProperty。
     */
    @JsonTypeName("tool_result")
    public record ToolResultBlock(
        @JsonProperty("tool_use_id") String toolUseId,
        String content,
        @JsonProperty("is_error") boolean isError
    ) implements ContentBlock {}

    /**
     * 规范化 contentBlocks，避免后续代码每次都判断 null。
     */
    public Message {
        contentBlocks = contentBlocks == null ? List.of() : List.copyOf(contentBlocks);
    }
}
