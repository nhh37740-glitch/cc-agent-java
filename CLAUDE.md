# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

**cc-agent-java** 是一个 Java + Spring Boot 学习项目，实现了一个能调用 DeepSeek API、读写文件、执行命令的 AI Agent。目标是学习 Java 和 Spring Boot 生态，为留日求职做准备。

代码注释面向 **C++ 背景的 Java 初学者** —— 用大白话解释 Java 概念，不假设读者熟悉 Spring 注解或 JVM。修改代码时请保持这个风格。

## 构建与运行

项目自带 JDK 17（`jdk17/jdk-17.0.14+7`）和 Gradle Wrapper（`gradlew.bat`），**不需要本机安装 Java 或 Gradle**。

```bat
REM 一键编译并启动（推荐 —— run.bat 会把项目内 JDK 设到临时 JAVA_HOME）
run.bat

REM 等价命令
gradlew.bat bootRun        REM 编译并启动 Spring Boot
gradlew.bat build          REM 打包成 build/libs/cc-agent-java-1.0.0.jar
gradlew.bat clean          REM 清理 build/ 目录
```

启动后服务监听 `http://localhost:8080`：
- 非流式：`POST /api/chat`，body `{"message": "..."}`
- 流式（SSE）：`GET /api/chat/stream?message=...`
- 调试前端：`http://localhost:8080/agent.html`

`build.gradle` 给 `compileJava` 加了 `-verbose` 和详细生命周期日志，编译时会打印每个源文件的解析、加载、检查、写入过程 —— 这是项目特意保留的，方便观察 Java 编译流程，不要移除。

## 整体架构

请求从外到内经过四层，**每一层都通过 Spring 注解和依赖注入连接**：

```
HTTP 请求
    ↓  (Spring MVC 路由)
ChatController          @RestController     —— 接收请求，返回响应
    ↓  @Autowired
AgentService            @Service            —— Agent 循环（最多 10 轮）
    ↓  @Autowired       │
    │                   ├──→ AnthropicClient   @Component  —— 调 DeepSeek API
    │                   │
    │                   └──→ ToolRegistry      @Component  —— 工具分发
    │                              │
    │                              └──→ FileReadTool / FileWriteTool / BashTool
    │                                          ↓  @Autowired
    │                                   PathValidator      —— 限制路径在 workspace-dir 内
```

### Agent 循环（AgentService）

最多 10 轮，每轮：
1. 把对话历史发给 `AnthropicClient.chat()` / `chatStream()`
2. 如果响应只有文本，结束并返回
3. 如果响应包含 `tool_calls`，遍历执行每个工具，把结果作为 `tool_result` 消息追加到历史，进入下一轮

流式（`runStream`）走相同流程，但 `AnthropicClient.chatStream()` 用 `BufferedReader` 逐行读 SSE 事件，每收到 `content_block_delta` 就回调 token 给 `SseEmitter`。

### API 兼容性

走的是 DeepSeek 的 **Anthropic 兼容端点** `https://api.deepseek.com/anthropic/v1/messages`，请求 / 响应格式和 Anthropic Messages API 一致（`x-api-key` + `anthropic-version: 2023-06-01`）。

`AnthropicClient.toApiMessage()` 把内部 `Message` 转成 API 需要的两种格式：
- 普通消息：`{"role": "user", "content": "..."}`
- 工具结果（`toolUseId != null`）：`{"role": "user", "content": [{"type": "tool_result", "tool_use_id": "...", "content": "..."}]}`

流式响应里，DeepSeek 偶尔会把工具参数嵌套在 `"input"` 键里 —— `AnthropicClient.chatStream()` 在 `content_block_stop` 分支里有兼容代码，看到 `inputNode.has("input")` 就自动解包，新增工具时注意这个细节。

### 工具注册

`ToolRegistry` 在构造函数里用 `@Autowired List<AgentTool>` 收集**所有实现 `AgentTool` 接口的 `@Component`**，再 stream 转成 `Map<工具名, 工具实例>`。**新增工具只需在 `com.example.ccagent.tool` 下加一个 `@Component implements AgentTool` 的类，Spring 启动时自动注册，不需要改 `ToolRegistry`。**

参数名约定：Anthropic 风格用 `path`，DeepSeek 偶尔用 `file_path` —— 现有 `FileReadTool`/`FileWriteTool` 用 `input.getOrDefault("path", input.get("file_path"))` 兼容两种，新工具请保持同样的取参方式。

## 关键代码约定

1. **注释用大白话**，面向 Java 初学者直接讲清这段代码做什么。禁止任何比喻、类比、打比方，也不要用其他语言或工具（C++、CMake、vcpkg、`int main()` 等）来类比；直接陈述技术细节即可。详见 [docs/comment-style.md](docs/comment-style.md)。
2. **依赖注入**：`@Component` / `@Service` 标记的类由 Spring 容器创建（默认单例），字段上的 `@Autowired` 连接到容器实例（传引用，不是拷贝）。不要 `new` 这些类。
3. **Record 用作不可变数据类**：`Message`、`ToolCall`、`ToolResult`、`ChatRequest`、`AgentProperties` 都是 record。编译器自动生成构造函数、getter（无 `get` 前缀，如 `props.apiKey()`）、`equals`、`hashCode`、`toString`。Record 隐式 final，不能加 `@Configuration`。
4. **配置绑定**：`application.yml` 里 `agent:` 下面的字段通过 `AgentProperties` 自动注入。横杠命名（`api-key`）→ 驼峰参数名（`apiKey`）。`CcAgentApplication` 上的 `@EnableConfigurationProperties(AgentProperties.class)` 把它注册为 Bean。
5. **日志**：用 SLF4J，每个类顶部 `private static final Logger log = LoggerFactory.getLogger(类名.class);`。日志级别在 `application.yml` 配置。

## 当前状态与已知问题

编译通过（`BUILD SUCCESSFUL`），`POST /api/chat` 工作正常。

- **`BashTool` 已禁用**：`BashTool.java:18` 的 `@Component` 被注释掉了。原因是 `Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command})` 在 Windows 上跑不了。如果要在 Windows 启用，需要切到 `cmd.exe /c` 或 PowerShell。
- **SSE 流式中文编码**：靠两处显式声明 UTF-8 保证不乱码 ——
  1. `AnthropicClient.java` 用 `new InputStreamReader(response.body(), StandardCharsets.UTF_8)` 解上游 DeepSeek 字节流（不传 charset 会用 JVM 默认编码，Windows 是 GBK）
  2. `ChatController.java` 的 `@GetMapping(produces = "text/event-stream;charset=UTF-8")` 让 Tomcat 按 UTF-8 写出（默认 `text/event-stream` 无 charset，Tomcat fallback 到 ISO-8859-1）

  这两处缺一不可。前端直接用 `fetch` + `TextDecoder('utf-8')` 解流，**不要**再加 Base64 之类的二次编码。
- **没有测试代码**：`src/test/` 目录不存在，`tasks.named('test')` 会无操作通过。

## API Key

`application.yml:13` 当前**硬编码了 DeepSeek API key**。这是学习项目里图省事的写法，注释里也写了"从环境变量读取"是目标但还没做。提交到任何公开仓库前必须先把它换成 `${DEEPSEEK_API_KEY}` 之类的占位符。

## 包结构

```
com.example.ccagent
├── CcAgentApplication       —— main 入口，@SpringBootApplication + @EnableAsync
├── controller/
│   └── ChatController       —— /api/chat, /api/chat/stream
├── service/
│   ├── AgentService         —— Agent 循环（同步 + 流式）
│   ├── AnthropicClient      —— HttpClient 调 DeepSeek API，解析 Anthropic 格式响应
│   ├── ToolRegistry         —— 自动收集所有 AgentTool 实现
│   └── PathValidator        —— resolve + normalize + startsWith 防止 ../ 越狱
├── tool/
│   ├── AgentTool            —— 工具接口（getName / getDescription / execute）
│   ├── FileReadTool         —— Files.readString
│   ├── FileWriteTool        —— Files.writeString
│   └── BashTool             —— 已禁用（@Component 注释掉了）
├── model/                   —— 全是 record
│   ├── Message              —— role + content + toolUseId
│   ├── ToolCall             —— id + name + input
│   ├── ToolResult           —— toolCallId + content + isError
│   └── ChatRequest          —— message
└── config/
    └── AgentProperties      —— @ConfigurationProperties(prefix = "agent")
```
