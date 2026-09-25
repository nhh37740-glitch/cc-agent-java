// package = 声明这个文件属于哪个包
package com.example.ccagent.tool;

// import = 引入其他包的类
// java.nio.file.Files = 文件读写工具类
import java.nio.file.Files;
// java.nio.file.Path = 文件路径类
import java.nio.file.Path;
// java.nio.charset.MalformedInputException = UTF-8 解码失败时可能抛出的异常
import java.nio.charset.MalformedInputException;
// java.nio.charset.StandardCharsets = 字符编码常量
import java.nio.charset.StandardCharsets;
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
 * 文件读取工具
 *
 * 作用：让 Anthropic 能读取文件内容
 * 例如：读取代码文件、配置文件等
 */
// @Component = 标记为 Spring 管理的组件
// implements AgentTool = 实现 AgentTool 接口（必须实现 getName、getDescription、execute 三个方法）
@Component
public class FileReadTool implements AgentTool {

    // @Autowired = 连接 Spring 容器里的 PathValidator 实例
    @Autowired
    private PathValidator pathValidator;

    // getName() = 返回工具名称，Anthropic 用这个名字调用工具
    @Override
    public String getName() {
        return "file_read";
    }

    // getDescription() = 返回工具描述，告诉 Anthropic 这个工具能做什么
    @Override
    public String getDescription() {
        return "读取指定路径的文件内容";
    }

    /**
     * 执行文件读取
     *
     * @param input 参数集合，需要包含 "path" 键
     * @return 文件内容的文本
     * @throws Exception 文件不存在或读取失败时抛出异常
     */
    @Override
    public String execute(Map<String, Object> input) throws Exception {
        // 兼容两种参数名：Anthropic 用 "path"，DeepSeek 用 "file_path"
        String rawPath = readPath(input);

        // pathValidator.validate() = 校验路径是否在工作目录内
        // 返回拼好的安全绝对路径，如果越权则抛 SecurityException
        String safePath = pathValidator.validate(rawPath);
        Path safeFile = Path.of(safePath);

        if (!Files.exists(safeFile)) {
            throw new IllegalArgumentException("文件不存在: " + rawPath);
        }
        if (Files.isDirectory(safeFile)) {
            throw new IllegalArgumentException("这是目录，请使用 file_list 查看目录内容: " + rawPath);
        }

        try {
            // Files.readString() = 按 UTF-8 读取文件全部内容
            return Files.readString(safeFile, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            throw new IllegalArgumentException("文件不是 UTF-8 文本或包含无法解码的字节: " + rawPath, e);
        }
    }

    private String readPath(Map<String, Object> input) {
        Object value = input.getOrDefault("path", input.get("file_path"));
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        return value.toString();
    }
}
