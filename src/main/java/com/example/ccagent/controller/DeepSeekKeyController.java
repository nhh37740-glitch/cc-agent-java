package com.example.ccagent.controller;

import com.example.ccagent.service.DeepSeekKeyService;
import com.example.ccagent.service.DeepSeekKeyService.KeyStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 网页密钥设置只作用于当前浏览器的服务端会话。 */
@RestController
@RequestMapping("/api/settings/deepseek")
public class DeepSeekKeyController {
    private final DeepSeekKeyService keys;
    private final DeepSeekRequestOriginPolicy origins;

    public DeepSeekKeyController(DeepSeekKeyService keys, DeepSeekRequestOriginPolicy origins) {
        this.keys = keys;
        this.origins = origins;
    }

    @GetMapping
    public ResponseEntity<KeyStatus> status(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(keys.status(request));
    }

    // 自定义请求头使跨站表单无法改写当前浏览器的设置。
    @PutMapping
    public ResponseEntity<KeyStatus> replace(@RequestHeader("X-Agent-Config") String marker,
            @RequestBody KeyRequest body, HttpServletRequest request) {
        requireMarker(marker);
        origins.requireSafeKeyWrite(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(keys.replace(request, body.apiKey()));
    }

    @DeleteMapping
    public ResponseEntity<KeyStatus> remove(@RequestHeader("X-Agent-Config") String marker,
            HttpServletRequest request) {
        requireMarker(marker);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(keys.remove(request));
    }

    private void requireMarker(String marker) {
        if (!"same-origin".equals(marker)) {
            throw new IllegalArgumentException("无效的配置请求");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
            .body(Map.of("error", exception.getMessage()));
    }

    public record KeyRequest(String apiKey) {
        @Override
        public String toString() {
            return "KeyRequest[apiKey=REDACTED]";
        }
    }
}
