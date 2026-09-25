// package = 声明这个文件属于哪个包
package com.example.ccagent.service;

// import = 引入其他包的类
// java.nio.file.Path = 文件路径类
import java.nio.file.Path;
// @Component = 标记为 Spring 管理的组件
import org.springframework.stereotype.Component;

// import = 引入项目内部的类
// AgentProperties = 配置绑定类
import com.example.ccagent.config.AgentProperties;

/**
 * 路径校验器
 *
 * 作用：限制工具只能访问工作目录内的文件，禁止"越狱"到系统目录。
 *
 * 原理：
 *   1. 拿到原始路径（如 "../etc/passwd"）
 *   2. 拼到工作目录后面（resolve）
 *   3. 规范化（normalize：去掉 .. 和 .）
 *   4. 检查结果是否还在工作目录内
 *
 * 示例（workspace-dir = "./workspace"）：
 *   validate("main.java")       → "/project/workspace/main.java" ✓
 *   validate("../etc/passwd")   → "/project/etc/passwd" ✗ 抛异常
 */
// @Component = 标记为 Spring 管理的组件
@Component
public class PathValidator {

    // 工作目录的绝对路径
    private final Path workspaceDir;

    // 构造函数：Spring 自动注入 AgentProperties
    public PathValidator(AgentProperties props) {
        // toAbsolutePath() = 把相对路径转成绝对路径
        // normalize() = 去掉路径里的 . 和 ..
        this.workspaceDir = Path.of(props.workspaceDir()).toAbsolutePath().normalize();
    }

    /**
     * 校验文件路径是否在工作目录内
     *
     * @param rawPath 原始路径（用户输入的）
     * @return 拼好的安全绝对路径
     * @throws SecurityException 路径超出工作目录时抛出
     */
    public String validate(String rawPath) {
        // resolve = 拼路径。如 workspaceDir="/workspace" + rawPath="src/main.java"
        //           → "/workspace/src/main.java"
        // normalize = 去掉 . 和 .. 。如 "/workspace/../etc" → "/etc"
        Path resolved = workspaceDir.resolve(rawPath).normalize();

        // startsWith = 检查 resolved 是否以 workspaceDir 开头
        // 如果用户传了 ../ 等路径，normalize 后会"越狱"出工作目录
        if (!resolved.startsWith(workspaceDir)) {
            throw new SecurityException("路径超出工作目录: " + rawPath);
        }

        return resolved.toString();
    }
}
