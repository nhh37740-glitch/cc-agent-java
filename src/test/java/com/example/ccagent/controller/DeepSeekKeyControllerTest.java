package com.example.ccagent.controller;

import com.example.ccagent.config.AgentProperties;
import com.example.ccagent.service.DeepSeekKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class DeepSeekKeyControllerTest {
    private static final String PATH = "/api/settings/deepseek";
    private static final String DEFAULT_KEY = "environment-example-key";
    private static final String SESSION_KEY = "browser-example-key";
    private MockMvc mvc;
    private DeepSeekKeyService keys;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties(
            "http://127.0.0.1/messages", DEFAULT_KEY, "model", 1000, ".", 1000, 1000);
        keys = new DeepSeekKeyService(properties);
        mvc = standaloneSetup(new DeepSeekKeyController(
            keys, new DeepSeekRequestOriginPolicy("127.0.0.1,::1"))).build();
    }

    @Test
    void settingIsPrivateToOneSessionAndRemovingItRestoresEnvironmentDefault() throws Exception {
        MockHttpSession first = new MockHttpSession();
        MockHttpSession second = new MockHttpSession();

        String initial = mvc.perform(get(PATH).session(first))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("environment"))
            .andReturn().getResponse().getContentAsString();
        assertThat(initial).doesNotContain(DEFAULT_KEY);

        String saved = mvc.perform(put(PATH).session(first)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "http://localhost")
                .header("Host", "localhost")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true))
            .andExpect(jsonPath("$.source").value("browser-session"))
            .andExpect(jsonPath("$.masked").value("••••••••"))
            .andReturn().getResponse().getContentAsString();
        assertThat(saved).doesNotContain(SESSION_KEY, DEFAULT_KEY);

        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
        firstRequest.setSession(first);
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.setSession(second);
        assertThat(keys.resolve(firstRequest)).isEqualTo(SESSION_KEY);
        assertThat(keys.resolve(secondRequest)).isEqualTo(DEFAULT_KEY);

        mvc.perform(get(PATH).session(second))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("environment"));

        mvc.perform(delete(PATH).session(first).header("X-Agent-Config", "same-origin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("environment"));
        assertThat(keys.resolve(firstRequest)).isEqualTo(DEFAULT_KEY);
    }

    @Test
    void invalidKeyIsRejectedWithoutEchoOrChangingSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String response = mvc.perform(put(PATH).session(session)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "http://localhost")
                .header("Host", "localhost")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"bad\\nkey\"}"))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("bad\\nkey");
        mvc.perform(get(PATH).session(session))
            .andExpect(jsonPath("$.source").value("environment"));
    }

    @Test
    void publicHttpRejectsKeyEvenWithMarkerOrSpoofedForwardedProtocol() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String response = mvc.perform(put(PATH).session(session)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "http://agent.example.test")
                .header("Host", "agent.example.test")
                .header("X-Forwarded-Proto", "https")
                .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SESSION_KEY);

        // 即使请求到达本机代理，浏览器来源仍是 HTTP 时也不能写入密钥。
        mvc.perform(put(PATH).session(session)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "http://agent.example.test")
                .header("Host", "agent.example.test")
                .header("X-Forwarded-Proto", "https")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isBadRequest());

        // Host 和 Origin 即使都伪装成本机，远端连接也不能走明文例外。
        mvc.perform(put(PATH).session(session)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "http://localhost")
                .header("Host", "localhost")
                .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(get(PATH).session(session))
            .andExpect(jsonPath("$.source").value("environment"));
    }

    @Test
    void httpsProxyRequiresTrustedPeerAndMatchingBrowserOrigin() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(put(PATH).session(session)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "https://other.example.test")
                .header("Host", "agent.example.test")
                .header("X-Forwarded-Proto", "https")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(put(PATH).session(session)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "https://agent.example.test")
                .header("Host", "agent.example.test")
                .header("X-Forwarded-Proto", "https")
                .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isBadRequest());

        mvc.perform(put(PATH).session(session)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "https://agent.example.test")
                .header("Host", "agent.example.test")
                .header("X-Forwarded-Proto", "https")
                .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isOk());

        mvc.perform(put(PATH).session(new MockHttpSession())
                .secure(true)
                .header("X-Agent-Config", "same-origin")
                .header("Origin", "https://agent.example.test")
                .header("Host", "agent.example.test")
                .with(request -> { request.setRemoteAddr("203.0.113.10"); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"" + SESSION_KEY + "\"}"))
            .andExpect(status().isOk());
    }
}
