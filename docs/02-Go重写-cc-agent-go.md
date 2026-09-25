# 02 · Go 语言重写（cc-agent-go）

> 用 Go 标准库重写 cc-agent-java 的核心链路。同一个项目逻辑，换一种语言实现，专注学 Go。

---

## 1. 任务目标

### 为什么做

Java 版阶段 1 已完成——存储、压缩、会话管理、工具调用都跑通了。业务逻辑不用重新想，精力全放在 Go 上。

Go 在日本招聘市场有需求（基础设施、CLI、网关），和 Java 互补。

### 目标

用 Go 标准库实现和 Java 版等价的 Agent，包括：

1. HTTP 服务（`net/http`，等价 Spring MVC + Tomcat）
2. DeepSeek API 调用（`net/http`，等价 `java.net.http.HttpClient`）
3. Agent 循环（goroutine，等价 `@Async` + `CompletableFuture`）
4. 工具注册与执行（interface，等价 `AgentTool` 接口）
5. 会话 JSON 存储（`encoding/json` + `os`，等价 Jackson + Files）
6. 记忆压缩（Compressor 回调，等价 Java 版实现）
7. 流式 SSE 响应（`net/http` flush，等价 `SseEmitter`）

不做：JPA/SQLite（Go 没有 ORM 标配，用 JSON 文件就够了）、前端页面（保持 Java 版的 agent.html）。

### Java ↔ Go 关键差异速查

| Java / Spring | Go | 说明 |
|---|---|---|
| `@RestController` | `http.HandleFunc` | Go 没有注解，路由手写 |
| `@Service` + `@Autowired` | 结构体 + 构造函数 | Go 没有 DI 容器，依赖通过构造函数传入 |
| `@Async` + `CompletableFuture` | `go func()` + `channel` | Go 原生异步，不需要线程池 |
| `record` | `struct` | Go 没有 record，struct 就是数据类 |
| `interface` | `interface` | 概念一致，Go 是隐式实现（不用 `implements`） |
| `ArrayList<Message>` | `[]Message` | Go 的 slice，语法更简洁 |
| `Jackson ObjectMapper` | `encoding/json` | Go 标准库自带 JSON，不需要第三方 |
| `Files.readString` | `os.ReadFile` | 都是标准库 |
| `SLF4J` | `log/slog`（Go 1.21+） | 结构化日志 |
| `@Transactional` | 无 | Go 没有声明式事务，手写 BEGIN/COMMIT |
| `SseEmitter` | `http.Flusher` | Go 标准库直接支持 SSE |
| Maven/Gradle | `go mod` | Go 自带依赖管理 |

---

## 2. 项目结构

```
cc-agent-go/
├── main.go              # 入口，注册路由，启动 HTTP 服务
├── go.mod               # 模块声明 + 依赖
├── controller/
│   └── chat.go          # HTTP handler：/api/chat, /api/chat/stream
├── service/
│   ├── agent.go         # Agent 循环（同步 + 流式）
│   ├── client.go        # DeepSeek API 调用（Anthropic 格式）
│   ├── store.go         # 会话 JSON 文件读写 + 压缩
│   └── trace.go         # Token 追踪日志
├── tool/
│   ├── tool.go          # Tool 接口定义
│   ├── file_read.go     # 读文件
│   ├── file_write.go    # 写文件
│   ├── file_list.go     # 列目录
│   ├── bash.go          # 白名单命令执行
│   └── registry.go      # 工具注册表
├── model/
│   └── types.go         # Message, ContentBlock, SessionJson 等
├── config/
│   └── config.go        # 配置加载（环境变量 + 默认值）
├── test-data/           # 测试用长文本 + 问题集（从 Java 版复制）
└── workspace/           # Agent 的工作目录
```

## 3. 实施步骤

### 3.1 搭骨架

1. `go mod init cc-agent-go`
2. `main.go`：注册路由，`http.ListenAndServe(":8080", nil)`
3. `POST /api/chat`：接收 JSON，返回 JSON（先返回写死的响应，确认 HTTP 通了）
4. `GET /api/chat/stream`：SSE 流式（先 push 写死的 token）

### 3.2 调 DeepSeek API

1. `config.go`：从环境变量读 `DEEPSEEK_API_KEY`，默认值兜底
2. `client.go`：`chat(history, tools, systemPrompt) -> response`
3. `client.go`：`chatStream(history, tools, systemPrompt, onToken) -> response`
4. `model/types.go`：定义 Message、ContentBlock、ToolCall、ToolResult 结构体

### 3.3 Agent 循环

1. `service/agent.go`：`Run(msg, conversationId)` — 同步版本
2. `service/agent.go`：`RunStream(msg, conversationId, w)` — 流式版本
3. 循环逻辑和 Java 版一致：调 API → 检查 tool_calls → 执行工具 → 下一轮

### 3.4 工具系统

1. `tool/tool.go`：定义 `Tool` 接口（`Name()`, `Description()`, `Execute(input) -> (string, error)`）
2. `tool/file_read.go`、`tool/file_write.go`、`tool/file_list.go`、`tool/bash.go`
3. `tool/registry.go`：收集所有工具实例，按名字查找
4. 路径安全检查（和 Java 版 `PathValidator` 等价）

### 3.5 会话存储

1. `service/store.go`：JSON 文件读写（`SessionJson` 结构体，和 Java 版同格式）
2. `LoadMessages(conversationId) -> []Message`
3. `AppendTurn(conversationId, messages, outputTokens, compressor)`
4. 压缩逻辑（Compressor 回调，和 Java 版等价）
5. 归档（老消息移到 `.archive.json`）

### 3.6 工具结果截断

和 Java 版一样，超长工具结果截断 + 附加提示。

### 3.7 Token 追踪日志

`service/trace.go`：独立文件记录每次 runningTotalTokens 变化，和 Java 版 `TokenTraceLogger` 等价。

## 4. 自测

```bash
go run main.go
```

1. `curl -X POST localhost:8080/api/chat -d '{"message":"hello"}'` → 返回文本
2. `curl "localhost:8080/api/chat/stream?message=hello"` → SSE 流式
3. 同一 conversationId 多发几轮 → 检查 `workspace/data/sessions/{id}.json`
4. 发长对话逼近压缩阈值 → 检查压缩触发、归档文件生成
