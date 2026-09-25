package com.example.ccagent.model;

/**
 * 前端响应体。
 *
 * conversationId 是本次请求所属的会话 ID。
 * reply 是 Agent 最终返回给用户的文本。
 */
public record ChatResponse(String conversationId, String reply) {}
