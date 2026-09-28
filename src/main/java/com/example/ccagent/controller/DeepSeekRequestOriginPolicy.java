package com.example.ccagent.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 检查浏览器来源和连接方式，不读取或记录 API Key。 */
@Component
public class DeepSeekRequestOriginPolicy {
    private final Set<String> trustedProxyAddresses;

    public DeepSeekRequestOriginPolicy(
            @Value("${DEEPSEEK_TRUSTED_PROXY_ADDRESSES:127.0.0.1,::1,0:0:0:0:0:0:0:1}") String addresses) {
        trustedProxyAddresses = Arrays.stream(addresses.split(","))
            .map(String::trim)
            .filter(address -> !address.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
    }

    public void requireSafeKeyWrite(HttpServletRequest request) {
        URI origin = parseOrigin(request.getHeader("Origin"));
        URI host = parseHost(request.getHeader("Host"));
        if (origin == null || host == null || !origin.getHost().equalsIgnoreCase(host.getHost())
                || effectivePort(origin, origin.getScheme()) != effectivePort(host, origin.getScheme())) {
            reject();
        }

        String scheme = origin.getScheme().toLowerCase();
        if ("http".equals(scheme) && isLoopbackHost(origin.getHost())
                && isLoopbackHost(request.getRemoteAddr())) {
            return;
        }
        // HTTPS 直连由 Servlet 确认。TLS 代理必须来自显式信任的 IP，
        // 且代理需要覆盖 X-Forwarded-Proto；仅有该请求头不能证明安全连接。
        if ("https".equals(scheme) && (request.isSecure()
                || (trustedProxyAddresses.contains(request.getRemoteAddr())
                    && "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))))) {
            return;
        }
        reject();
    }

    private URI parseOrigin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            if (("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return uri;
            }
        } catch (RuntimeException ignored) {
            // 缺失或格式错误的 Origin 一律拒绝。
        }
        return null;
    }

    private URI parseHost(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create("http://" + value);
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                return uri;
            }
        } catch (RuntimeException ignored) {
            // 缺失或格式错误的 Host 一律拒绝。
        }
        return null;
    }

    private int effectivePort(URI uri, String scheme) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String plain = host.startsWith("[") && host.endsWith("]")
            ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equalsIgnoreCase(plain) || "::1".equals(plain)
                || "0:0:0:0:0:0:0:1".equals(plain)) {
            return true;
        }
        String[] octets = plain.split("\\.");
        if (octets.length != 4 || !"127".equals(octets[0])) {
            return false;
        }
        for (String octet : octets) {
            if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private void reject() {
        throw new IllegalArgumentException("请从同源 HTTPS 或本机回环地址设置 DeepSeek API Key");
    }
}
