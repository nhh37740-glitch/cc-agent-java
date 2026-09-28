package com.example.ccagent.controller;

import com.example.ccagent.config.AgentProperties;
import com.example.ccagent.model.ChatRequest;
import com.example.ccagent.model.ChatResponse;
import com.example.ccagent.service.AgentService;
import com.example.ccagent.service.DeepSeekKeyService;
import com.example.ccagent.service.StreamFlowLogger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerKeyTest {
    @Test
    void chatAndStreamPassCurrentSessionKeyToAsyncAgentService() {
        AgentProperties properties = new AgentProperties(
            "http://127.0.0.1/messages", "environment-example-key", "model", 1000, ".", 1000, 1000);
        DeepSeekKeyService keys = new DeepSeekKeyService(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        keys.replace(request, "browser-example-key");

        AgentService agent = mock(AgentService.class);
        ChatController controller = new ChatController();
        ReflectionTestUtils.setField(controller, "agentService", agent);
        ReflectionTestUtils.setField(controller, "deepSeekKeys", keys);
        ReflectionTestUtils.setField(controller, "streamFlowLogger", mock(StreamFlowLogger.class));

        ChatResponse answer = new ChatResponse("conversation-1", "ok");
        when(agent.run("hello", "conversation-1", "browser-example-key"))
            .thenReturn(CompletableFuture.completedFuture(answer));
        assertThat(controller.chat(new ChatRequest("hello", "conversation-1"), request).join())
            .isEqualTo(answer);
        verify(agent).run("hello", "conversation-1", "browser-example-key");

        SseEmitter emitter = controller.chatStream("hello", "conversation-1", request);
        verify(agent).runStream("hello", "conversation-1", emitter, "browser-example-key");
        emitter.complete();
    }
}
