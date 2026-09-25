# cc-agent-java 工程化图文生成 Prompt

## Prompt 1：线程模型图

```
生成一个 ASCII art 线程模型图，展示 cc-agent-java Spring Boot 应用的完整线程视角。

要求：
1. 用 ASCII art 画出以下线程池和线程的工作流程
2. 标注每个线程池的线程数（默认值）和来源
3. 用箭头标注数据流方向
4. 标注线程在什么时刻被阻塞、什么时刻释放

线程结构：

java.exe 进程（操作系统层面只有一个进程）
│
├── [主线程] main() → SpringApplication.run()
│     ├── 创建 Spring 容器
│     ├── 创建所有 @Component 对象
│     ├── 处理 @Autowired 依赖
│     ├── 启动 Tomcat
│     └── 主线程结束（但进程不退出，因为还有非守护线程）
│
├── [Tomcat 线程池] 默认 200 个线程
│     │
│     │  请求来了：
│     │  ├── 从池里拿空闲线程
│     │  ├── 在该线程上执行 ChatController.chat()
│     │  │   ├── 调用 agentService.run() → 立即返回 CompletableFuture
│     │  │   └── 线程立即释放，归还线程池  ← 关键！
│     │  └── 等待 @Async 完成 → 返回 HTTP 响应
│     │
│     │  如果没有 @Async：
│     │  ├── ChatController.chat() 直接同步调用 agentService.run()
│     │  ├── run() 里要等 HTTP 响应（可能 2-5 秒）
│     │  └── 线程被阻塞 2-5 秒 ← 这期间不能处理其他请求
│     │
│     └── 线程空闲时在池子里等待下一个请求
│
├── [@Async 线程池] 默认 8 个线程（由 @EnableAsync 启用）
│     │
│     │  有 @Async 任务时：
│     │  ├── 把 AgentService.run() 包装成任务放入队列
│     │  ├── 线程池取出任务
│     │  ├── 在该线程上执行 run()：
│     │  │   ├── 第 1 轮：anthropicClient.chat(history)
│     │  │   │   └── 发 HTTP POST → 线程阻塞等待 Claude API 响应
│     │  │   ├── 如果有 tool_use：
│     │  │   │   └── toolRegistry.execute() → 执行工具（可能阻塞在磁盘 I/O）
│     │  │   ├── 第 2 轮：anthropicClient.chat(history)
│     │  │   │   └── 线程再次阻塞等待 HTTP 响应
│     │  │   ├── 如果无 tool_use：
│     │  │   │   └── CompletableFuture.complete(result) → Tomcat 收到通知
│     │  │   └── 线程释放，归还线程池
│     │  │
│     │  └── 线程空闲时在池子里等待下一个 @Async 任务
│     │
│     └── 多个 @Async 线程可以并发执行（用户 A 和用户 B 同时处理）
│
└── [GC 线程] JVM 自动管理的垃圾回收线程

用时间轴展示一次完整请求中各线程的状态变化：
- T0: Tomcat线程3 空闲
- T1: 请求到达 → Tomcat线程3 执行 ChatController.chat() → 返回 CompletableFuture → 线程释放
- T2: @Async线程C 执行 AgentService.run() → 发 HTTP → 线程阻塞
- T3: HTTP 响应到达 → @Async线程C 继续执行 → 解析 JSON → 执行工具 → 再发 HTTP → 线程再次阻塞
- T4: 第二次 HTTP 响应到达 → @Async线程C 继续 → 无工具调用 → 循环结束 → 线程释放
- T5: Tomcat 检测到 CompletableFuture 完成 → 返回 HTTP 响应给用户
```

## Prompt 2：模块依赖图

```
生成一个 mermaid C4 Container 图，展示 cc-agent-java Spring Boot 应用的模块依赖关系。

要求：
1. 用 mermaid C4 语法（Container Diagram）
2. 标注每个模块的职责（一行话）
3. 标注模块之间的依赖方向
4. 标注 Spring 的 @Autowired 注入关系
5. 区分"请求时调用"和"启动时连接"

模块和依赖关系：

外部系统：
- 用户（User）：通过 HTTP 访问
- Claude API：外部 LLM 服务

容器内模块：
- ChatController：接收 HTTP 请求，路由到 AgentService
  依赖：AgentService（通过 @Autowired 启动时连接）
  被调用：用户发 POST 请求时

- AgentService：核心业务逻辑，Agent 循环
  依赖：AnthropicClient（通过 @Autowired 启动时连接）
  依赖：ToolRegistry（通过 @Autowired 启动时连接）
  被调用：ChatController 收到请求时

- AnthropicClient：调用 Claude API
  依赖：AgentProperties（通过 @Autowired 启动时连接）
  依赖：Java HttpClient（内置，无需注入）
  被调用：AgentService 在循环中调用

- ToolRegistry：管理所有工具，按名字查找并执行
  依赖：List<AgentTool>（通过 @Autowired 构造函数注入所有工具实例）
  被调用：AgentService 需要执行工具时

- AgentTool（接口）：工具契约
  实现者：FileReadTool, FileWriteTool, BashTool
  被 ToolRegistry 收集，被 AgentService 间接调用

- FileReadTool：读取文件
  依赖：Java Files API（内置，无需注入）

- FileWriteTool：写入文件
  依赖：Java Files API（内置，无需注入）

- BashTool：执行命令
  依赖：Java Runtime API（内置，无需注入）

- AgentProperties：配置对象
  依赖：application.yml（Spring Boot 自动映射）
  被注入：AnthropicClient 需要配置时

外部基础设施：
- Tomcat 线程池：Spring Boot 自动创建和管理
- @Async 线程池：由 @EnableAsync 启用，Spring Boot 自动创建

关系标注：
- "启动时连接"（@Autowired 注入）：AgentService → AnthropicClient, ToolRegistry
- "请求时调用"（方法调用）：ChatController → AgentService, AgentService → AnthropicClient, AgentService → ToolRegistry
- "实现接口"：FileReadTool → AgentTool
- "读取配置"：AnthropicClient → AgentProperties
- "自动管理"：Spring Boot → Tomcat 线程池, @Async 线程池
```

## Prompt 3：Agent 循环时序图

```
生成一个 mermaid Sequence Diagram，展示 cc-agent-java 中一次完整的 Agent 工具调用请求。

参与者：
- 用户（User）
- Tomcat 线程（Tomcat Thread）
- @Async 线程（Async Thread）
- AnthropicClient（LLM Client）
- Claude API（Claude API）
- ToolRegistry（Tool Registry）
- FileReadTool（File Tool）

流程：
T0: 用户 → ChatController: POST /api/chat {"message": "帮我读 Main.java"}
T1: Tomcat 线程 → AgentService: run("帮我读 Main.java")
T2: agentService.run() 返回 CompletableFuture
T3: Tomcat 线程释放（可以处理其他请求）

T4: @Async 线程 → AnthropicClient: chat(history)
T5: AnthropicClient → Claude API: HTTP POST（带 messages 列表）
T6: Claude API → AnthropicClient: JSON 响应（含 tool_use: file_read）
T7: AnthropicClient → @Async 线程: AnthropicResponse(toolCalls=[file_read])
T8: @Async 线程 → ToolRegistry: execute("file_read", {path: "Main.java"})
T9: ToolRegistry → FileReadTool: execute({path: "Main.java"})
T10: FileReadTool → 文件系统: Files.readString()
T11: 文件系统 → FileReadTool: 文件内容
T12: FileReadTool → ToolRegistry: 文件内容
T13: ToolRegistry → @Async 线程: ToolResult(isError=false, content="public class Main {...}")
T14: @Async 线程 → AnthropicClient: chat(history) [含文件内容]
T15: AnthropicClient → Claude API: HTTP POST（含文件内容）
T16: Claude API → AnthropicClient: JSON 响应（纯文本，无 tool_use）
T17: AnthropicClient → @Async 线程: AnthropicResponse(text="文件内容是...", toolCalls=[])
T18: @Async 线程: CompletableFuture.complete(result)

T19: Tomcat 线程检测到 CompletableFuture 完成
T20: Tomcat 线程 → 用户: JSON {"reply": "文件内容是..."}

用不同颜色标注：
- 蓝色：Tomcat 线程执行的任务
- 绿色：@Async 线程执行的任务
- 橙色：网络 I/O（可能阻塞）
- 红色：磁盘 I/O（可能阻塞）
```

## 使用方式

1. 复制上面的 Prompt
2. 粘贴到支持 mermaid 的 AI 工具或 https://mermaid.live
3. Prompt 1 是 ASCII art，直接看
4. Prompt 2 和 3 是 mermaid 语法，复制到 mermaid.live 渲染
