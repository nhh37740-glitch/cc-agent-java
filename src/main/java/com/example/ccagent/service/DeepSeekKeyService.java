package com.example.ccagent.service;

import com.example.ccagent.config.AgentProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

/** 只在服务端会话保存网页提交的密钥；环境变量仍是默认值。 */
@Service
public class DeepSeekKeyService {
    private static final String SESSION_KEY = DeepSeekKeyService.class.getName() + ".apiKey";

    private final AgentProperties properties;

    public DeepSeekKeyService(AgentProperties properties) {
        this.properties = properties;
    }

    public String resolve(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object override = session == null ? null : session.getAttribute(SESSION_KEY);
        if (override instanceof String key) {
            return key;
        }
        return properties.apiKey() == null ? "" : properties.apiKey();
    }

    public KeyStatus status(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        boolean browserKey = session != null && session.getAttribute(SESSION_KEY) instanceof String;
        boolean environmentKey = properties.apiKey() != null && !properties.apiKey().isBlank();
        String source = browserKey ? "browser-session" : environmentKey ? "environment" : "none";
        return new KeyStatus(browserKey || environmentKey, source, browserKey || environmentKey ? "••••••••" : "");
    }

    public KeyStatus replace(HttpServletRequest request, String value) {
        // 禁止空值、控制字符及过长输入进入 HTTP 请求头。错误消息不包含输入值。
        if (value == null || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalArgumentException("DeepSeek API Key 格式无效");
        }
        request.getSession(true).setAttribute(SESSION_KEY, value);
        return status(request);
    }

    public KeyStatus remove(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
        return status(request);
    }

    public record KeyStatus(boolean configured, String source, String masked) {}
}
