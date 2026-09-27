package com.example.ccagent.model;

/**
 * 前端请求体
 *
 * 当用户发送 POST /api/chat {"message": "帮我看 main.java"} 时
 * Spring 自动把 JSON 转成这个对象
 *
 * 转换原理：Jackson 库根据 JSON 的键名匹配 Record 的构造参数名。
 *   JSON {"message": "帮我看 main.java", "conversationId": "test-1"}
 *   → ChatRequest(String message, String conversationId)
 *   键名必须和构造参数名一致，否则转换失败
 */
public record ChatRequest(String message, String conversationId) {}
