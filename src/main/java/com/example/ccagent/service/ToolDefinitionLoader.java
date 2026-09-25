package com.example.ccagent.service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

import com.example.ccagent.tool.AgentTool;

import jakarta.annotation.PostConstruct;

/**
 * 工具定义加载器。
 *
 * 这个类只负责读取 tools.xml，把 XML 里的工具描述和参数结构转换成 API 需要的格式。
 * 工具真正怎么执行，仍然由各个 AgentTool 实现类负责。
 */
@Component
public class ToolDefinitionLoader {

    private static final Logger log = LoggerFactory.getLogger(ToolDefinitionLoader.class);

    private final Map<String, Map<String, Object>> definitionsByName = new LinkedHashMap<>();

    /**
     * Spring 创建 Bean 后自动调用这个方法。
     *
     * 这里提前读取 XML，启动时就能发现 XML 写错的问题。
     */
    @PostConstruct
    public void load() {
        try {
            ClassPathResource resource = new ClassPathResource("tools.xml");
            if (!resource.exists()) {
                throw new IllegalStateException("classpath 下没有找到 tools.xml");
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
                readTools(document);
            }

            log.info("工具 XML 定义加载完成，count: {}, names: {}",
                definitionsByName.size(), definitionsByName.keySet());
        } catch (Exception e) {
            throw new IllegalStateException("读取 tools.xml 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按 Java 已注册工具的顺序生成工具定义。
     *
     * XML 找得到定义时使用 XML；找不到时用工具类自己的描述作为兜底。
     */
    public List<Map<String, Object>> buildToolDefinitions(List<AgentTool> tools) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> registeredNames = new HashSet<>();

        for (AgentTool tool : tools) {
            registeredNames.add(tool.getName());
            Map<String, Object> definition = definitionsByName.get(tool.getName());
            if (definition == null) {
                log.warn("tools.xml 缺少工具定义，使用工具类描述兜底，tool: {}", tool.getName());
                result.add(fallbackDefinition(tool));
            } else {
                result.add(deepCopyDefinition(definition));
            }
        }

        for (String xmlName : definitionsByName.keySet()) {
            if (!registeredNames.contains(xmlName)) {
                log.warn("tools.xml 中存在未注册的工具定义，tool: {}", xmlName);
            }
        }

        log.info("工具定义已从 XML 组装，registeredTools: {}, apiDefinitions: {}",
            tools.size(), result.size());
        return result;
    }

    private void readTools(Document document) {
        definitionsByName.clear();
        NodeList toolNodes = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < toolNodes.getLength(); i++) {
            Node node = toolNodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !"tool".equals(node.getNodeName())) {
                continue;
            }

            Element toolElement = (Element) node;
            String name = toolElement.getAttribute("name").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("tool 节点缺少 name");
            }

            definitionsByName.put(name, readToolDefinition(toolElement, name));
        }
    }

    private Map<String, Object> readToolDefinition(Element toolElement, String name) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", name);
        definition.put("description", firstChildText(toolElement, "description"));
        definition.put("input_schema", readInputSchema(toolElement));
        return definition;
    }

    private Map<String, Object> readInputSchema(Element toolElement) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        NodeList children = toolElement.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE || !"parameter".equals(node.getNodeName())) {
                continue;
            }

            Element parameter = (Element) node;
            String name = parameter.getAttribute("name").trim();
            if (name.isEmpty()) {
                throw new IllegalArgumentException("parameter 节点缺少 name");
            }

            String type = parameter.getAttribute("type").trim();
            if (type.isEmpty()) {
                type = "string";
            }

            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", type);
            property.put("description", firstChildText(parameter, "description"));
            properties.put(name, property);

            if ("true".equalsIgnoreCase(parameter.getAttribute("required"))) {
                required.add(name);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
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

    private Map<String, Object> fallbackDefinition(AgentTool tool) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", tool.getName());
        definition.put("description", tool.getDescription());
        definition.put("input_schema", schema);
        return definition;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyDefinition(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> mapValue) {
                copy.put(entry.getKey(), deepCopyDefinition((Map<String, Object>) mapValue));
            } else if (value instanceof List<?> listValue) {
                copy.put(entry.getKey(), new ArrayList<>(listValue));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }
}
