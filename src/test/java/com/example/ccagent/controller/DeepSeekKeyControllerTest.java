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
        mvc = standaloneSetup(new DeepSeekKeyController(keys)).build();
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
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"apiKey\":\"bad\\nkey\"}"))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("bad\\nkey");
        mvc.perform(get(PATH).session(session))
            .andExpect(jsonPath("$.source").value("environment"));
    }
}
