# LangChain4j 集成架构分析

## 一、当前架构 vs LangChain4j 架构

### 当前架构

```
Controller → AgentService（手动循环）→ AnthropicClient（HTTP）→ Claude API
                  │
                  ├── 手动管理 List<Message> 历史
                  ├── 手动调用 ToolRegistry.execute()
                  └── 手动 for 循环（最多 10 轮）
```

**问题：** AgentService 包含了太多手动逻辑——构建消息、调 API、解析响应、执行工具、循环——全部手写。

### LangChain4j 架构

```
Controller → @AiService（声明式接口）→ 自动代理 → ChatModel → Claude API
                  │
                  ├── ChatMemoryProvider 自动管理记忆
                  ├── @Tool 注解自动发现并执行
                  └── AiServices 自动处理 Agent 循环
```

**优势：** AgentService 的手动循环被声明式接口取代，LangChain4j 自动处理消息构建、工具执行、记忆管理。

---

## 二、组件映射表

| 当前组件 | LangChain4j 组件 | 操作 |
|---------|-----------------|------|
| `AnthropicClient`（HTTP 调用） | `ChatModel` bean | **删除**（由 LangChain4j 自动配置） |
| `AgentService`（手动循环） | `@AiService` 接口 | **替换**（声明式代理） |
| `ToolRegistry`（工具注册） | `@Tool` 注解 | **删除**（自动发现） |
| `AgentTool` 接口 | `@Tool` 方法 | **删除**（注解取代接口） |
| `Message`、`ToolCall`、`ToolResult` | LangChain4j 内置类型 | **删除**（框架内置） |
| `AgentProperties` | `application.yml` 配置 | **简化** |
| `ChatController` | 注入 `@AiService` | **简化** |
| `FileReadTool` 等 | 添加 `@Tool` 方法 | **修改**（保留 execute()，新增 @Tool 方法） |

---

## 三、数据流对比

### 当前（手动）

```
用户消息
  → ChatController
  → AgentService.run()
    → 创建 List<Message> history
    → for 循环（max 10 轮）:
        → AnthropicClient.chat(history)  // HTTP 调用
        → 解析 response.toolCalls()
        → 如果有工具调用:
            → ToolRegistry.execute(name, input)
            → history.add(toolResult)
        → 如果无工具调用:
            → 返回 response.text()
    → 返回结果
```

### LangChain4j（自动）

```
用户消息
  → ChatController
  → @AiService.chat(message)
    → 自动：
      → ChatMemoryProvider.getMemory()  // 加载历史
      → ChatModel.chat(request)          // 调用 API（自动注入系统提示词）
      → 如果有 toolExecutionRequests:
          → 自动执行 @Tool 方法
          → 自动添加 ToolExecutionResultMessage
          → 自动循环（直到纯文本）
      → 返回 AiMessage.text()
      → ChatMemoryProvider.updateMessages()  // 保存历史
```

---

## 四、新的架构草图

```
┌────────────────────────────────────────────────────────────┐
│                    Spring Boot Application                  │
│                                                            │
│  ┌──────────────┐     ┌────────────────────────────┐     │
│  │ChatController│────→│    @AiService 接口          │     │
│  │ /api/chat/v2 │     │    CcAgentAiService        │     │
│  │ /api/chat/   │     │    .chat(message)          │     │
│  │   v2/stream  │     │    .chatStream(message)    │     │
│  └──────────────┘     └──────────┬─────────────────┘     │
│                                  │                        │
│                    LangChain4j 自动代理                    │
│                                  │                        │
│              ┌───────────────────┼───────────────┐        │
│              ▼                   ▼               ▼        │
│     ┌────────────┐    ┌──────────────┐   ┌──────────┐   │
│     │ ChatModel  │    │ ChatMemory   │   │ @Tool 们 │   │
│     │(Anthropic) │    │ Provider     │   │ bash     │   │
│     │(自动配置)   │    │ (会话记忆)    │   │ file_read│   │
│     └─────┬──────┘    └──────────────┘   │ file_write│  │
│           │                              └──────────┘   │
│           ▼                                              │
│    ┌──────────────┐                                      │
│    │ Claude API   │                                      │
│    └──────────────┘                                      │
└────────────────────────────────────────────────────────────┘
```

---

## 五、实施计划

### 步骤 1：添加依赖（build.gradle）

```gradle
// 新增两个依赖
implementation 'dev.langchain4j:langchain4j-anthropic-spring-boot-starter:1.15.1-beta25'
implementation 'dev.langchain4j:langchain4j-spring-boot-starter:1.15.1-beta25'
```

### 步骤 2：修改 application.yml

```yaml
# 新增 langchain4j 配置段
langchain4j:
  anthropic:
    chat-model:
      api-key: ${ANTHROPIC_API_KEY}
      model-name: claude-sonnet-4-20250514
      max-tokens: 4096
```

### 步骤 3：修改工具类（添加 @Tool 方法）

在每个工具类新增一个 `@Tool` 方法，委托给现有的 `execute()`：

```java
// BashTool.java 新增
@Tool("执行 shell 命令")
public String runCommand(@P("要执行的命令") String command) throws Exception {
    return execute(Map.of("command", command));
}
```

### 步骤 4：创建 @AiService 接口

```java
@AiService
public interface CcAgentAiService {
    @SystemMessage("You are an AI coding assistant...")
    String chat(String userMessage);
    TokenStream chatStream(String userMessage);
}
```

### 步骤 5：修改 ChatController（新增 /v2 端点）

新端点调用 `CcAgentAiService`，旧端点保留不动。

### 步骤 6：编译验证

```bash
gradlew.bat compileJava
```

---

## 六、迁移策略

| 阶段 | 操作 |
|------|------|
| **Phase 1** | 添加依赖 + 配置 + @Tool 方法 + @AiService（旧代码保留） |
| **Phase 2** | 新增 `/api/chat/v2` 端点，和旧的 `/api/chat` 并行运行 |
| **Phase 3** | 验证新端点稳定后，删除：AgentService、AnthropicClient、ToolRegistry、Message、ToolCall、ToolResult、AgentTool |
| **Phase 4** | 去掉 v2 后缀，新端点成为默认 |

---

## 七、关键收益

| 维度 | 改进 |
|------|------|
| **代码量** | 删除 ~6 个类，减少 ~300 行手动代码 |
| **工具定义** | `@Tool` 注解 + `@P` 参数描述 → 自动生成 JSON Schema |
| **Agent 循环** | 自动处理，不需要手写 for 循环 |
| **记忆管理** | ChatMemory 带自动逐出策略，支持多用户 |
| **流式支持** | TokenStream 开箱即用，不需要手动 SSE 解析 |
| **扩展性** | 新增工具只需加 @Tool 方法，不需要改其他代码 |
