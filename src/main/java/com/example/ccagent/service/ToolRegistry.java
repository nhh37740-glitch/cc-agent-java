// package = 声明这个文件属于哪个包
package com.example.ccagent.service;

// import = 引入其他包的类
// java.util.List = 有序列表，用于接收所有工具实例
import java.util.List;
// java.util.Map = 键值对集合，用于按工具名快速查找
import java.util.Map;
// java.util.stream.Collectors = 流处理工具，用于把 List 转成 Map
import java.util.stream.Collectors;

// import = 引入 Spring 的组件标记和依赖注入
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

// import = 引入日志
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// import = 引入项目内部的类
import com.example.ccagent.tool.AgentTool;
import com.example.ccagent.model.ToolResult;

/**
 * 工具注册表
 *
 * 作用：自动收集所有工具实例，按名字快速查找并执行
 *
 * 工作流程：
 *   1. Spring 启动时，自动找到所有带 @Component 且实现了 AgentTool 接口的类
 *   2. 创建每个工具的实例，注入到 List<AgentTool> toolList
 *   3. 把 List 转成 Map（key=工具名，value=工具实例）
 *   4. AgentService 调用 execute("file_read", input) 时，按名字查找工具并执行
 */
// @Component = 标记为 Spring 管理的组件
@Component
public class ToolRegistry {

    // Logger = 日志对象，记录工具注册和执行
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    // Map = 键值对集合
    // String = 键的类型（工具名，如 "file_read"）
    // AgentTool = 值的类型（工具实例）
    // tools = 变量名
    private final Map<String, AgentTool> tools;

    // toolList = Spring 注入的工具实例列表，用来按注册顺序生成 API 工具定义
    private final List<AgentTool> toolList;

    // toolDefinitionLoader = 读取 tools.xml，生成模型能看到的工具描述和参数结构
    private final ToolDefinitionLoader toolDefinitionLoader;

    /**
     * 构造函数，Spring 自动调用
     *
     * @Autowired = 连接 Spring 容器里的所有 AgentTool 实例到这个参数
     * Spring 会自动扫描所有带 @Component 且实现了 AgentTool 的类
     * 例如：FileReadTool、FileWriteTool、BashTool 等
     *
     * @param toolList Spring 注入的工具实例列表
     */
    @Autowired
    public ToolRegistry(List<AgentTool> toolList, ToolDefinitionLoader toolDefinitionLoader) {
        this.toolList = List.copyOf(toolList);
        this.toolDefinitionLoader = toolDefinitionLoader;

        // toolList.stream() = 把 List 转成流
        // .collect(Collectors.toMap(...)) = 把流转成 Map
        // AgentTool::getName = 用工具的 getName() 方法作为键
        // t -> t = 值就是工具实例本身
        // 结果：{"file_read" → FileReadTool, "file_write" → FileWriteTool, ...}
        this.tools = toolList.stream()
            .collect(Collectors.toMap(AgentTool::getName, t -> t));
    }

    /**
     * 执行工具
     *
     * @param name 工具名称（如 "file_read"）
     * @param input 工具参数（键值对）
     * @return ToolResult 工具执行结果
     */
    public ToolResult execute(String name, Map<String, Object> input) {
        // tools.get(name) = 按名字从 Map 里查找工具实例
        AgentTool tool = tools.get(name);

        // 如果找不到这个工具，返回错误结果
        if (tool == null) {
            log.warn("工具不存在: {}", name);
            return new ToolResult(name, "工具不存在: " + name, true);
        }

        try {
            // tool.execute(input) = 调用工具的执行方法
            String result = tool.execute(input);
            log.debug("工具 {} 执行成功，结果长度: {}", name, result.length());
            return new ToolResult(name, result, false);
        } catch (Exception e) {
            // e.getMessage() 对某些异常会返回 null。
            // 用 e.toString() 记录异常类型，再把异常对象 e 传给 SLF4J，日志里会带完整堆栈。
            log.warn("工具 {} 执行失败: {}", name, e.toString(), e);
            // 返回给模型的错误信息同样不能是 null：message 为空时回退到异常类名，
            // 否则模型收到一个「null」也无法判断下一步该怎么办。
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new ToolResult(name, "执行失败: " + reason, true);
        }
    }

    /**
     * 获取所有工具定义（Claude API 格式）
     *
     * 返回格式：
     * [
     *   { "name": "file_read",  "description": "...", "input_schema": {...} },
     *   { "name": "file_write", "description": "...", "input_schema": {...} },
     *   { "name": "bash",       "description": "...", "input_schema": {...} }
     * ]
     *
     * 这些定义来自 tools.xml。
     * Java 工具类只负责执行，模型看到的描述和参数结构统一放在 XML 里维护。
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return toolDefinitionLoader.buildToolDefinitions(toolList);
    }
}
