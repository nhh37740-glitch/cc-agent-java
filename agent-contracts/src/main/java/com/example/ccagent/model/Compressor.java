package com.example.ccagent.model;

import java.util.List;

/**
 * 记忆压缩器。
 *
 * SessionStore 不直接依赖 AnthropicClient，只依赖这个接口。
 * AgentService 在调用 appendTurn 时传入实现（lambda），把 LLM 能力注入进来。
 */
@FunctionalInterface
public interface Compressor {
    /**
     * @param oldMessages 需要压缩的老消息列表
     * @return 摘要文本
     * @throws Exception 压缩失败时抛异常，SessionStore 会 catch 并跳过压缩
     */
    String compress(List<Message> oldMessages) throws Exception;
}
