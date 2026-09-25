package com.example.ccagent.model;

/**
 * 工具执行结果
 *
 * toolCallId: 关联到哪个 ToolCall（同一个 ID）
 * content:    执行结果的文本内容
 * isError:    是否执行失败
 *
 * 为什么需要 isError？
 *   工具执行可能失败（文件不存在、命令执行报错等）
 *   需要区分成功和失败，Claude 会根据错误信息决定下一步
 */
public record ToolResult(String toolCallId, String content, boolean isError) {}
