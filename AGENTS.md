# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## 项目定位

**cc-agent-java** 是一个 Java + Spring Boot 学习项目，实现了一个能调用 DeepSeek API、读写文件、执行命令的 AI Agent。目标是学习 Java 和 Spring Boot 生态，为留日求职做准备。

代码注释面向 **C++ 背景的 Java 初学者** —— 用大白话解释 Java 概念，不假设读者熟悉 Spring 注解或 JVM。修改代码时请保持这个风格。

## 模块与构建

Codex 不在本机运行 Gradle 或 `javac`。标准构建和运行验证由 Linux Jenkins 节点内的 Docker 流水线完成：`scripts/release.py` 先运行 `scripts/check_boundaries.py`，再调用 Gradle 构建并生成可运行 JAR 与 SHA-256 manifest；Jenkins 随后归档产物并构建运行镜像。部署由 `Jenkinsfile` 的 `DeployDemo` 参数控制，默认关闭。具体操作见 [README.md](README.md)。

`DEEPSEEK_API_KEY` 由运行环境注入，不能写入仓库或镜像。服务提供 `POST /api/chat`、SSE `GET /api/chat/stream`、会话读取接口和 `/agent.html` 页面。

`build.gradle` 保留 `compileJava` 的 `-verbose` 与生命周期日志，用于服务端构建诊断。

## 模块与整体架构

`agent-contracts/` 是 9 个共享 FQCN 的唯一源码所有者：消息与会话模型、`Compressor` 和 `AgentTool`。根应用通过 `implementation project(':agent-contracts')` 使用这些类型；不要在 `src/main/java` 重新声明它们。`scripts/check_boundaries.py` 会拒绝重复类型，并检查契约模块没有反向依赖 Spring、持久层或应用服务。

根应用负责 HTTP 控制器、Agent 流程、DeepSeek 客户端、会话持久化和具体工具。请求从外到内经过以下组件：

```
HTTP 请求
    ↓ Spring MVC
ChatController          —— 接收请求并返回响应
    ↓
AgentService            —— Agent 循环
    ├── AnthropicClient  —— 调用 DeepSeek API
    └── ToolRegistry     —— 分发 contracts 中定义的 AgentTool 实现
        └── FileReadTool / FileWriteTool / FileListTool / BashTool
            └── PathValidator —— 限制文件操作在 workspace-dir 内
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

模块边界由静态检查维护；编译、运行与端到端验证以服务端 Jenkins/Docker 流水线结果为准。

- **`BashTool` 已启用**：工具名是 `bash`，但不会把整段命令交给系统 shell 自由解释。它固定在 `workspace-dir` 下执行，并带白名单：
  - `git` 只允许 `status/diff/log/show/branch/rev-parse/ls-files/grep`
  - `rg` 用于搜索
  - `gradlew.bat/gradlew` 只允许常见构建任务
  - `mkdir/touch/rm/del/rmdir/mv/move/cp/copy` 由 Java 代码执行，并逐个校验路径不能越出 workspace
- **SSE 流式中文编码**：靠两处显式声明 UTF-8 保证不乱码 ——
  1. `AnthropicClient.java` 用 `new InputStreamReader(response.body(), StandardCharsets.UTF_8)` 解上游 DeepSeek 字节流（不传 charset 会用 JVM 默认编码，Windows 是 GBK）
  2. `ChatController.java` 的 `@GetMapping(produces = "text/event-stream;charset=UTF-8")` 让 Tomcat 按 UTF-8 写出（默认 `text/event-stream` 无 charset，Tomcat fallback 到 ISO-8859-1）

  这两处缺一不可。前端直接用 `fetch` + `TextDecoder('utf-8')` 解流，**不要**再加 Base64 之类的二次编码。
- **没有 Java 自动化测试源码**：当前 `src/test/` 不存在，Gradle 的 `test` 任务不提供行为覆盖。`scripts/check_boundaries.py` 是静态架构门禁，不应描述为 Java 单元测试。

## API Key

`application.yml` 当前使用 `${DEEPSEEK_API_KEY}` 环境变量占位符，不包含默认密钥。运行或发布时由环境提供真实 Key，不能写入仓库或镜像。

## 模块结构

```
agent-contracts/src/main/java/com/example/ccagent
├── model/                 —— ChatRequest、ChatResponse、ConversationSummary、Compressor、Message、SessionJson、ToolCall、ToolResult
└── tool/AgentTool         —— 具体工具实现遵循的共享接口

src/main/java/com/example/ccagent
├── CcAgentApplication
├── controller/ChatController
├── config/AgentProperties
├── service/               —— AgentService、AnthropicClient、SessionStore、ToolRegistry 等
├── repository/             —— JSONL 备份
├── model/JsonlBackupEntity —— 应用专属持久化实体
└── tool/                   —— FileReadTool、FileWriteTool、FileListTool、BashTool
```

共享类型只能从 `agent-contracts` 引入。`src/main/java` 中的 `model` 与 `tool` 目录只保存应用专属实现，不要复制共享 FQCN。
