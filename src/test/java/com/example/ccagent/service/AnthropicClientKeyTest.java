package com.example.ccagent.service;

import com.example.ccagent.config.AgentProperties;
import com.example.ccagent.model.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicClientKeyTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void explicitSessionKeyReachesProviderHeaderAndLegacyCallsKeepEnvironmentFallback() throws Exception {
        List<String> receivedKeys = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/messages", exchange -> {
            try {
                receivedKeys.add(exchange.getRequestHeaders().getFirst("x-api-key"));
                exchange.getRequestBody().readAllBytes();
                byte[] response = "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();

        AgentProperties properties = new AgentProperties(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/messages",
            "environment-example-key", "model", 1000, ".", 1000, 1000);
        AgentConfigLoader config = mock(AgentConfigLoader.class);
        when(config.systemPrompt()).thenReturn("system");
        when(config.agentMemoryRule()).thenReturn("rule");
        AnthropicClient client = new AnthropicClient();
        ReflectionTestUtils.setField(client, "properties", properties);
        ReflectionTestUtils.setField(client, "agentConfig", config);

        List<Message> history = List.of(new Message("user", List.of(new Message.TextBlock("hello"))));
        assertThat(client.chat(history, List.of(), "", 4096, "browser-example-key").text())
            .isEqualTo("ok");
        assertThat(client.chat(history, List.of()).text()).isEqualTo("ok");
        assertThat(receivedKeys).containsExactly("browser-example-key", "environment-example-key");
    }
}
