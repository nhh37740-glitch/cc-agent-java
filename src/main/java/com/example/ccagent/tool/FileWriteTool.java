// package = 声明这个文件属于哪个包
package com.example.ccagent.tool;

// import = 引入其他包的类
// java.nio.file.Files = 文件读写工具类
import java.nio.file.Files;
// java.nio.file.Path = 文件路径类
import java.nio.file.Path;
// java.util.Map = 键值对集合，用于接收工具参数
import java.util.Map;
// @Component = 标记为 Spring 管理的组件
import org.springframework.stereotype.Component;
// @Autowired = 连接 Spring 容器里的实例
import org.springframework.beans.factory.annotation.Autowired;

// import = 引入项目内部的类
// PathValidator = 路径安全校验
import com.example.ccagent.service.PathValidator;

/**
 * 文件写入工具
 *
 * 作用：让 Anthropic 能写入文件
 * 例如：创建新文件、修改代码等
 */
// @Component = 标记为 Spring 管理的组件
// implements AgentTool = 实现 AgentTool 接口
@Component
public class FileWriteTool implements AgentTool {

    // @Autowired = 连接 Spring 容器里的 PathValidator 实例
    @Autowired
    private PathValidator pathValidator;

    // getName() = 返回工具名称
    @Override
    public String getName() {
        return "file_write";
    }

    // getDescription() = 返回工具描述
    @Override
    public String getDescription() {
        return "将内容写入指定路径的文件";
    }

    /**
     * 执行文件写入
     *
     * @param input 参数集合，需要包含：
     *   - "path":    文件路径（String 类型）
     *   - "content": 要写入的内容（String 类型）
     * @return 执行结果的文本
     * @throws Exception 写入失败时抛出异常
     */
    @Override
    public String execute(Map<String, Object> input) throws Exception {
        // 兼容两种参数名：Anthropic 用 "path"，DeepSeek 用 "file_path"
        String rawPath = readPath(input);

        // 从参数中取出要写入的内容
        String content = readContent(input);

        // pathValidator.validate() = 校验路径是否在工作目录内
        String safePath = pathValidator.validate(rawPath);
        Path safeFile = Path.of(safePath);
        Path parent = safeFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Files.writeString() = 写入文件
        Files.writeString(safeFile, content);

        return "文件已写入: " + rawPath;
    }

    private String readPath(Map<String, Object> input) {
        Object value = input.getOrDefault("path", input.get("file_path"));
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        return value.toString();
    }

    private String readContent(Map<String, Object> input) {
        Object value = input.get("content");
        if (value == null) {
            throw new IllegalArgumentException("content 不能为空");
        }
        return value.toString();
    }
}
