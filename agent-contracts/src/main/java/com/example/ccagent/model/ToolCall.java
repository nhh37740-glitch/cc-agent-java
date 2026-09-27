package com.example.ccagent.model;

import java.util.Map;

/**
 * Claude 返回的工具调用请求
 *
 * 当 Claude 决定调用工具时，会返回这样的结构：
 *   { id: "call_1", name: "file_read", input: { path: "main.java" } }
 *
 * id:     调用 ID（唯一标识这次调用，用于关联结果）
 * name:   工具名称（对应 AgentTool 接口的 getName() 返回值）
 * input:  工具参数（键值对，不同工具参数不同）
 *
 * Map<String, Object> 说明：
 *   键固定是 String，值可以是任何类型（String、Integer、Boolean 等）
 *   因为不同工具的参数结构不同，用 Map 保持灵活性
 */
public record ToolCall(String id, String name, Map<String, Object> input) {}
