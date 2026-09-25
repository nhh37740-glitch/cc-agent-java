# cc-agent-java UML 类图生成 Prompt

## 完整类图

```
生成一个 PlantUML 类图，展示 cc-agent-java 项目中所有类的完整关系。

使用以下 PlantUML 语法生成类图，包含：
1. 所有类、接口、Record
2. 每个类的字段和方法
3. 类之间的关系（实现接口、依赖注入、方法调用）
4. 包的边界
5. Spring 注解标记

======= PlantUML 代码 =======

@startuml
skinparam packageStyle rectangle
skinparam class {
  BackgroundColor White
  BorderColor #4169e1
}

' ==================== model 包 ====================
package "model (数据层)" {
  record Message {
    + role: String
    + content: String
  }

  record ToolCall {
    + id: String
    + name: String
    + input: Map~String,Object~
  }

  record ToolResult {
    + toolCallId: String
    + content: String
    + isError: boolean
  }

  record ChatRequest {
    + message: String
  }
}

' ==================== tool 包 ====================
package "tool (工具层)" {
  interface AgentTool <<interface>> {
    + getName(): String
    + getDescription(): String
    + execute(Map): String
  }

  class FileReadTool <<@Component>> {
    + getName(): String
    + getDescription(): String
    + execute(Map): String
  }

  class FileWriteTool <<@Component>> {
    + getName(): String
    + getDescription(): String
    + execute(Map): String
  }

  class BashTool <<@Component>> {
    + getName(): String
    + getDescription(): String
    + execute(Map): String
  }
}

' ==================== service 包 ====================
package "service (业务层)" {
  class AnthropicClient <<@Component>> {
    - properties: AgentProperties
    - httpClient: HttpClient
    - objectMapper: ObjectMapper
    + chat(List~Message~): AnthropicResponse
    + chatStream(List~Message~, Consumer): void
  }

  class AnthropicResponse <<inner record>> {
    + text: String
    + toolCalls: List~ToolCall~
  }

  class ToolRegistry <<@Component>> {
    - tools: Map~String, AgentTool~
    + ToolRegistry(List~AgentTool~)
    + execute(String, Map): ToolResult
  }

  class AgentService <<@Service>> {
    - anthropicClient: AnthropicClient
    - toolRegistry: ToolRegistry
    + run(String): CompletableFuture~String~
    + runStream(String, SseEmitter): void
  }
}

' ==================== controller 包 ====================
package "controller (接口层)" {
  class ChatController <<@RestController>> {
    - agentService: AgentService
    + chat(ChatRequest): CompletableFuture~Map~
    + chatStream(String): SseEmitter
  }
}

' ==================== config 包 ====================
package "config (配置层)" {
  record AgentProperties <<@ConfigurationProperties>> {
    + apiEndpoint: String
    + apiKey: String
    + model: String
    + maxToolRounds: int
  }
}

' ==================== 入口 ====================
package "入口" {
  class CcAgentApplication <<@SpringBootApplication @EnableAsync>> {
    + main(String[]): void
  }
}

' ==================== 关系 ====================
' 接口实现
FileReadTool  ..|>  AgentTool
FileWriteTool ..|>  AgentTool
BashTool       ..|>  AgentTool

' @Autowired 注入（实线 + 箭头）
ChatController --> AgentService : <<@Autowired>>
AgentService   --> AnthropicClient : <<@Autowired>>
AgentService   --> ToolRegistry : <<@Autowired>>
AnthropicClient --> AgentProperties : <<@Autowired>>

' 构造函数注入
ToolRegistry ..> AgentTool : <<@Autowired 构造函数注入所有实例>>

' 内部类
AnthropicClient +-- AnthropicResponse : 内部 Record

' 方法调用
ChatController ..> ChatRequest : 使用
AgentService   ..> Message : 使用
AgentService   ..> ToolCall : 使用
AgentService   ..> ToolResult : 使用
ToolRegistry   ..> ToolResult : 使用
AnthropicClient ..> Message : 使用
AnthropicClient ..> ToolCall : 使用

' 入口关系
CcAgentApplication ..> ChatController : 扫描启动

@enduml
```

复制到 https://www.plantuml.com/plantuml/ 渲染。

## 类关系文字版（备查）

```
┌─────────────────────────────────────────────────────────────┐
│  入口                                                        │
│  CcAgentApplication.java                                     │
│  @SpringBootApplication  @EnableAsync                        │
│  main() 启动一切                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  配置                                                        │
│  AgentProperties.java  (Record)                              │
│  @ConfigurationProperties(prefix="agent")                    │
│  字段: apiEndpoint, apiKey, model, maxToolRounds             │
│  来源: application.yml 自动映射                               │
│                                                              │
│  被注入到: AnthropicClient                                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  HTTP 接口层                                                 │
│  ChatController.java  (@RestController)                      │
│  字段: agentService (AgentService)    ← @Autowired           │
│                                                              │
│  方法:                                                       │
│    POST /api/chat        → chat(ChatRequest):Future<Map>     │
│    GET  /api/chat/stream → chatStream(@RequestParam):SseEmitter│
│                                                              │
│  依赖: AgentService                                           │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  业务逻辑层                                                  │
│  AgentService.java  (@Service)                               │
│  字段:                                                       │
│    anthropicClient  ← @Autowired                             │
│    toolRegistry     ← @Autowired                             │
│                                                              │
│  方法:                                                       │
│    run(userMessage): CompletableFuture<String>  @Async       │
│    runStream(userMessage, SseEmitter): void     @Async       │
│                                                              │
│  核心逻辑: for 循环 (Agent 循环)                              │
│    → anthropicClient.chat()                                  │
│    → 检查 toolCalls                                          │
│    → toolRegistry.execute()                                  │
│    → 循环直到纯文本                                           │
└─────────────────────────────────────────────────────────────┘

┌──────────────────────────┬──────────────────────────────────┐
│  API 调用层               │  工具管理层                       │
│                           │                                  │
│  AnthropicClient.java     │  ToolRegistry.java               │
│  (@Component)             │  (@Component)                    │
│                           │                                  │
│  字段:                     │  字段:                            │
│    properties ← @Autowired│    tools: Map<String,AgentTool>  │
│    httpClient             │                                  │
│    objectMapper           │  构造函数:                        │
│                           │    @Autowired                    │
│  方法:                     │    ToolRegistry(List<AgentTool>) │
│    chat(history): Resp    │                                  │
│                           │  方法:                            │
│  依赖: AgentProperties    │    execute(name, input): Result  │
└──────────────────────────┴──────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  数据层 (都是 Record)                                        │
│                                                              │
│  ChatRequest  ──── 用户 HTTP 请求体                           │
│  Message      ──── 对话消息                                   │
│  ToolCall     ──── Claude 返回的工具调用请求                   │
│  ToolResult   ──── 工具执行后的结果                            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  工具层                                                      │
│                                                              │
│  AgentTool (interface)   ← 定义三个方法                       │
│    getName(), getDescription(), execute()                    │
│                                                              │
│  FileReadTool  (@Component, implements AgentTool)            │
│  FileWriteTool (@Component, implements AgentTool)            │
│  BashTool      (@Component, implements AgentTool)            │
│                                                              │
│  被 ToolRegistry 通过 @Autowired 构造函数自动收集             │
└─────────────────────────────────────────────────────────────┘
```

## 关系标注图

```
画一张图，用不同颜色和线型标注各类关系的含义：

实线箭头 → ：@Autowired 注入（Spring 启动时连接，传引用）
虚线箭头 ⇢ ：方法调用（运行时调用）
空心三角 ▷ ：实现接口（implements）
包含 +-- ：内部类或 Record

图例：
━━━→  @Autowired 注入（字段/构造函数）
- - →  运行时方法调用
- - ▷  实现接口 (implements)

使用这个图例重新画一个简化版的关系图，只保留关键连接：

          ┌──────────┐
          │ AgentTool │ (interface)
          │ <<接口>>  │
          └──────────┘
           △  △  △  (implements)
           │  │  │
  ┌────────┘  │  └────────┐
  │           │           │
┌──────────┐┌──────────┐┌──────────┐
│FileRead  ││FileWrite ││ BashTool │ (@Component)
│Tool      ││Tool      ││          │
└──────────┘└──────────┘└──────────┘
  │           │           │
  └───────────┼───────────┘
              │ @Autowired 构造函数注入 (List<AgentTool>)
              ▼
        ┌──────────┐
        │ToolRegistry│ (@Component)
        └──────────┘
              │
              │ @Autowired 字段注入
              ▼
        ┌──────────┐       ┌──────────────┐
        │AgentService│─────→│AnthropicClient│ (@Component)
        │(@Service)  │@Auto │              │
        └──────────┘  wired└──────┬───────┘
              ▲                   │ @Autowired
              │ @Autowired        ▼
              │            ┌──────────────┐
        ┌──────────┐       │AgentProperties│
        │ChatController│     │(Record)      │
        │(@RestController)│ └──────────────┘
        └──────────┘
```

## 使用方式

1. 复制 PlantUML 代码到 https://www.plantuml.com/plantuml/
2. 或者粘贴给支持 PlantUML 的 AI 工具
3. 关系标注图可以直接看，不需要渲染
