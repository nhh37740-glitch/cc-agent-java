package com.example.ccagent.service;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import jakarta.annotation.PostConstruct;

/**
 * Agent 运行配置加载器。
 *
 * 这个类读取 agent.xml，集中管理系统提示词、长期记忆规则和工具调用轮数。
 */
@Component
public class AgentConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigLoader.class);

    private String systemPrompt;
    private String agentMemoryRule;
    private int maxToolRounds;

    /**
     * Spring 创建 Bean 后自动读取 XML。
     *
     * agent.xml 是 Agent 行为配置，缺失或写错时直接启动失败。
     */
    @PostConstruct
    public void load() {
        try {
            ClassPathResource resource = new ClassPathResource("agent.xml");
            if (!resource.exists()) {
                throw new IllegalStateException("classpath 下没有找到 agent.xml");
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            try (InputStream input = resource.getInputStream()) {
                Document document = builder.parse(input);
                document.getDocumentElement().normalize();
                readAgentConfig(document.getDocumentElement());
            }

            log.info("agent.xml 加载完成，maxToolRounds: {}, systemPromptChars: {}, agentMemoryRuleChars: {}, hasAgentMdRule: {}",
                maxToolRounds, systemPrompt.length(), agentMemoryRule.length(), agentMemoryRule.contains("memory/AGENT.MD"));
        } catch (Exception e) {
            throw new IllegalStateException("读取 agent.xml 失败: " + e.getMessage(), e);
        }
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public String agentMemoryRule() {
        return agentMemoryRule;
    }

    public int maxToolRounds() {
        return maxToolRounds;
    }

    private void readAgentConfig(Element root) {
        systemPrompt = requiredText(root, "system-prompt");
        agentMemoryRule = requiredText(root, "agent-memory-rule");
        maxToolRounds = readPositiveInt(requiredText(root, "max-tool-rounds"), "max-tool-rounds");
    }

    private String requiredText(Element root, String tagName) {
        String value = firstChildText(root, tagName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(tagName + " 不能为空");
        }
        return value;
    }

    private String firstChildText(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return node.getTextContent().trim();
            }
        }
        return "";
    }

    private int readPositiveInt(String rawValue, String fieldName) {
        try {
            int value = Integer.parseInt(rawValue);
            if (value <= 0) {
                throw new IllegalArgumentException(fieldName + " 必须大于 0");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " 必须是整数: " + rawValue, e);
        }
    }
}
