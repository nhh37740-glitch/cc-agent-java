// package = 声明这个文件属于哪个包
package com.example.ccagent.tool;

// import = 引入其他包的类
// java.util.Map = 键值对集合，用于接收工具参数
import java.util.Map;

/**
 * 工具接口
 *
 * 作用：定义所有工具必须实现的契约
 *
 * 所有工具（FileReadTool、FileWriteTool、BashTool）都必须实现这个接口
 * AgentService 通过这个接口调用工具，不关心具体是哪个工具
 */
// interface = 接口，定义所有工具必须实现的方法
// AgentTool = 接口名
public interface AgentTool {

    /**
     * 工具名称，Anthropic 用这个名字调用工具
     * 例如："file_read"、"file_write"、"bash"
     */
    // String = 返回类型（字符串）
    // getName = 方法名（必须和接口定义的一样）
    // () = 没有参数
    String getName();

    /**
     * 工具描述，告诉 Anthropic 这个工具能做什么
     * Anthropic 根据这个描述决定什么时候该用这个工具
     */
    String getDescription();

    /**
     * 执行工具
     *
     * @param input 工具参数（键值对）
     * @return 执行结果的文本
     * @throws Exception 执行失败时抛出异常
     */
    // Map<String, Object> = 参数类型（键值对集合）
    // String = 键的类型（参数名，如 "path"）
    // Object = 值的类型（参数值，可以是任何类型）
    String execute(Map<String, Object> input) throws Exception;
}
