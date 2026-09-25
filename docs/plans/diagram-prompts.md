# 架构图生成 Prompt 集合

## Prompt 1：整体系统架构图

使用 **mermaid** 语法：

```
请生成一个 mermaid 架构图，展示 LangChain4j 集成后的 Java Spring Boot Agent 系统：

- 左侧是外部系统（用户浏览器/curl、Claude API、Anthropic API）
- 中间上方是 Spring Boot Application 边界：
  - ChatController（HTTP 端点，/api/chat 和 /api/chat/v2）
  - CcAgentAiService（@AiService 声明式接口）
- 中间是 LangChain4j 自动代理层：
  - ChatModel（封装 Anthropic HTTP 调用）
  - ChatMemory（管理对话历史，TokenWindow 逐出策略）
  - @Tool 工具集合（bash、file_read、file_write）
- 下方是基础设施：
  - Tomcat（Web 服务器）
  - Spring IoC（对象工厂）
  - application.yml（配置）
- 用箭头标注数据流方向
- 用虚线框标注"Spring Boot 自动管理"的组件
- 用不同颜色区分"手动编写"和"框架自动生成"
```

## Prompt 2：数据流图（一次完整请求）

使用 **mermaid** 语法：

```
请生成一个 mermaid sequence diagram（时序图），展示用户发 "帮我读 main.java" 到 Java Agent 的完整数据流：

参与者：用户、ChatController、@AiService代理、ChatMemory、ChatModel、Claude API、BashTool

流程：
1. 用户 → ChatController：GET /api/chat/v2?message=帮我读main.java
2. ChatController → @AiService代理：chat("帮我读main.java")
3. @AiService代理 → ChatMemory：加载历史
4. @AiService代理 → ChatModel：chat(request) [含系统提示词+历史+工具列表]
5. ChatModel → Claude API：HTTP POST /v1/messages
6. Claude API → ChatModel：tool_use(name="file_read", path="main.java")
7. @AiService代理检测到 tool_use → FileReadTool.readFile("main.java")
8. 工具返回文件内容
9. @AiService代理 → ChatMemory：添加工具结果
10. @AiService代理 → ChatModel：chat(request) [含工具结果]
11. ChatModel → Claude API：HTTP POST
12. Claude API → ChatModel：纯文本 "文件内容是..."
13. @AiService代理 → ChatMemory：保存最终回复
14. @AiService代理 → ChatController：返回结果
15. ChatController → 用户：JSON 响应

用实线箭头标注同步调用，虚线箭头标注异步返回
用注释标注"LangChain4j 自动处理"的部分
```

## Prompt 3：组件替换对比图

使用 **mermaid** 或 **ASCII art**：

```
请生成一个对比图，展示 LangChain4j 集成前后组件的变化：

左侧（集成前，手动实现）：
- AnthropicClient（150行，HTTP客户端）
- AgentService（100行，手动for循环）
- ToolRegistry（80行，工具注册表）
- AgentTool接口 + 3个实现类
- Message/ToolCall/ToolResult 数据类
→ 共 7 个文件

右侧（集成后，LangChain4j）：
- ChatModel bean（0行，自动配置）
- @AiService接口（15行，声明式）
- @Tool注解方法（+3行/工具）
- ChatMemoryProvider（5行，配置）
→ 共 2 个新文件，删除 7 个旧文件

用颜色标注：
- 红色 = 要删除的组件
- 绿色 = 新增的组件
- 黄色 = 修改的组件
- 灰色 = 不变的组件
```

## Prompt 4：LangChain4j 内部代理机制图

使用 **mermaid**：

```
请生成一个 mermaid 图展示 LangChain4j 的 @AiService 代理内部机制：

1. 用户定义 @AiService 接口（只有方法签名，没有实现类）
2. Spring Boot 启动时，LangChain4j 扫描到这个接口
3. LangChain4j 用 Java 动态代理（java.lang.reflect.Proxy）创建一个代理对象
4. 代理对象内部组装了：
   - ChatModel（调用 LLM）
   - ChatMemoryProvider（管理记忆）
   - ToolProvider（收集 @Tool 方法）
5. 当用户调用 service.chat(message) 时，代理执行：
   a. 从 ChatMemoryProvider 获取当前会话的记忆
   b. 构建 ChatRequest（系统提示词 + 记忆 + 工具列表）
   c. 调用 ChatModel.chat(request)
   d. 检查响应中的 toolExecutionRequests
   e. 如果有 → 执行 @Tool 方法 → 添加结果 → 回到步骤 b
   f. 如果是纯文本 → 添加到记忆 → 返回
6. 用户完全不需要知道这些内部步骤的存在

用箭头标注自动流程
```

## Prompt 5：C++ vs Java 概念映射图

使用 **ASCII art 表格或 mermaid**：

```
请生成一个概念映射图，以帮助 C++ 开发者理解 LangChain4j 架构：

| 层次 | C++ 概念 | Java/LangChain4j 概念 |
|------|---------|---------------------|
| 构建 | CMake + vcpkg | Gradle + Maven Central |
| 编译 | .cpp → .o → 链接 → .exe | .java → .class → JAR |
| 运行 | OS 直接执行 | JVM 虚拟机 |
| 框架 | （无直接对应） | Spring Boot（对象工厂） |
| HTTP | libcurl / Boost.Beast | Tomcat（内嵌服务器） |
| LLM调用 | 手写HTTP+JSON解析 | ChatModel（自动配置） |
| 对话记忆 | std::vector<Message> | ChatMemory（自动逐出） |
| 工具 | 函数指针表/virtual | @Tool注解（自动发现） |
| Agent循环 | 手写while循环 | AiServices（自动代理） |
| 流式 | 帧缓冲+回调 | TokenStream + onPartialResponse |

用图表形式展示
```

---

## 使用方式

1. 复制上面的 Prompt
2. 粘贴到支持 mermaid 的 AI 工具（如 Claude、ChatGPT 等）或 mermaid 在线编辑器（https://mermaid.live）
3. 生成的图可以复制到项目文档中
