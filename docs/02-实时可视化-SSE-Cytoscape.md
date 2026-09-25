# 02 · 实时可视化（SSE + Cytoscape）

> 做一个前端，把 Agent 内部"组件怎么协作、数据怎么流"用**节点图 + 动画**实时画出来——不是日志、不是表格，是看得见的数据流。顺带学会**命名 SSE 事件**。

---

## 1. 任务目标与背景

### 痛点

现在的调试前端 `agent.html` 只能看两样东西：流式 token 文本、原始 SSE 日志表格。但 Spring 框架最值钱的部分——**依赖注入、异步、SSE、组件如何协作**——你一个都看不见。学一个框架却看不到它的骨架在动，太亏了。

### 目标

做一个新前端 `agent-viz.html`，左边一张**节点图**（浏览器 / Controller / AgentService / AnthropicClient / DeepSeek / ToolRegistry 六个节点），右边一个**信息面板**。当你发一条消息：

- 节点**按顺序亮起**（浏览器 → Controller → AgentService → ...），边上有**流动动画**表示数据在走；
- 右侧实时显示：第几轮、token 流、拼了什么 prompt、调了哪个工具、记忆压缩日志。

**怎么做到**：在现有的 `/api/chat/stream` 上，除了发 token，再多发几种**带名字的 SSE 事件**（`stage`、`prompt`、`toolCall`...），前端按事件名分别更新对应区块。

> 这一篇依赖 [01](01-跨请求记忆-SQLite-JPA.md)（要可视化历史内容、要有 conversationId 输入框），所以**先做完 01 再做 02**。

---

## 2. 要学的技术 + 基本语法

### 2.1 SSE 命名事件

- **作用**：SSE（Server-Sent Events）是服务器**单向**往浏览器推数据的长连接。"命名事件"就是给每条推送贴一个**事件名**，前端能按名字分流处理。
- **最小语法**（一条 SSE 报文长这样）：

```
event: toolCall
data: {"name":"file_read","input":{"path":"a.txt"}}

```

- **C++ 类比**：像一条**单向的、带类型标签的消息总线**（发布-订阅），只不过载体是 HTTP 长连接、内容是纯文本。中间件里的 MQ topic / 消息类型字段，思路一致——`event:` 就是 topic，`data:` 就是 payload。

### 2.2 后端构造命名事件：`SseEmitter.event().name().data()`

- **作用**：Spring 用 `SseEmitter` 往外推 SSE。`.event()` 是个 builder，能设事件名。
- **最小语法**：

```java
emitter.send(SseEmitter.event()
    .name("toolCall")                    // 事件名
    .data(payloadObject)                 // 内容（Spring 自动 JSON 序列化）
    .build());
```

- **C++ 类比**：像往一个输出流里写一帧"带 header 的消息"。builder 模式 ≈ C++ 里链式 setter 配置对象。

### 2.3 前端接收：`EventSource` + `addEventListener`

- **作用**：`EventSource` 是**浏览器内置**的 SSE 客户端，自动帮你连、自动重连、自动按行拆帧。
- **最小语法**：

```javascript
const es = new EventSource('/api/chat/stream?message=hi&conversationId=xxx');
es.addEventListener('toolCall', (e) => {
    const data = JSON.parse(e.data);   // e.data 是字符串，要 parse
    console.log('调用了工具', data.name);
});
es.addEventListener('done', () => es.close());
```

- **C++ 类比**：像一个**帮你管好 socket 长连接、自动分包、按消息类型回调**的订阅客户端。你只管注册"收到某类型消息就干什么"，连接细节它全包了。

### 2.4 回调解耦：`Consumer<T>`

- **作用**：让下层组件（`AnthropicClient`）能"通知"上层发生了什么，但**不直接依赖**上层（`SseEmitter`）。下层收一个回调，上层在调用时把回调塞进去。
- **最小语法**：

```java
// 下层：只知道"有个回调要调"，不知道回调里干嘛
public AnthropicResponse chatStream(..., Consumer<String> onToken) {
    onToken.accept(tokenText);   // 每收到一个 token 就回调
}
// 上层：调用时塞一个 lambda 进去
client.chatStream(..., token -> emitter.send(token));
```

- **C++ 类比**：就是 `std::function<void(T)>` 做回调。**控制反转**——下层不认识上层，避免了"底层耦合顶层"的坏味道。中间件里的回调/观察者模式，完全一致。

### 2.5 Cytoscape.js

- **作用**：一个专门画"**节点 + 边**"图的 JavaScript 库。节点、边、布局、动画都是它的一等概念，画图比用 D3 省 80% 代码。
- **最小语法**：

```javascript
const cy = cytoscape({
    container: document.getElementById('graph'),
    elements: [
        { data: { id: 'controller', label: 'ChatController' } },  // 节点
        { data: { source: 'controller', target: 'agent' } }       // 边
    ],
    style: [ /* 节点/边的样式 */ ],
    layout: { name: 'preset' }   // 用我们指定的坐标
});
cy.getElementById('controller').addClass('active');  // 让节点变激活态
```

- **C++ 类比**：像一个**现成的图形渲染引擎**——你只负责喂"数据 + 样式表"，渲染、布局、动画它全干。类比 Qt 的 QGraphicsScene，但专门为节点图优化。

---

## 3. 实施步骤

### 3.1 后端：加一个 emit helper（`AgentService`）

不用 AOP（学习项目，AspectJ 杀鸡用牛刀），手动在观测点发事件：

```java
private void emit(SseEmitter emitter, String name, Object payload) {
    try {
        emitter.send(SseEmitter.event().name(name).data(payload).build());
    } catch (IOException e) {
        // 浏览器断开了，静默忽略——绝不能因为可视化通道断了影响主对话
    }
}
```

> ⚠️ `emit` 失败必须静默 catch。可视化是"旁路观测"，断了不能拖垮业务主流程。

### 3.2 后端：事件 schema（前端和 Gemini 提示词都依赖这张表）

| event 名 | payload | 触发点 |
|----------|---------|--------|
| `request` | `{conversationId, userMessage, timestamp}` | `ChatController` 入口 |
| `stage` | `{name:"controller"\|"agent"\|"api"\|"tool"\|"done", status:"enter"\|"exit"}` | 各层进入/离开 |
| `prompt` | `{system, historyLen, toolCount, estimatedTokens, compressed}` | 拼完请求体后 |
| `round` | `{n, total:10}` | Agent 每轮开始 |
| `token` | 一段 JSON 编码的字符串（沿用现有） | 流式 text_delta |
| `toolCall` | `{id, name, input}` | 工具解析完成 |
| `toolResult` | `{id, name, isError, contentPreview}` | 工具执行返回后 |
| `compress` | `{beforeTokens, afterTokens, summarized}` | 触发记忆压缩时 |
| `error` | `{message, stage}` | catch 块 |
| `done` | `{totalRounds, finalTokens, elapsedMs}` | `complete()` 前 |

> **不发完整历史快照**（流量爆炸）。前端要看历史，单独调 `GET /api/conversations/{id}`（01 已经加好这个端点）。

### 3.3 后端：观测点埋点 + `AnthropicClient` 的 prompt 回调

- `AgentService.java`：在循环每轮开始 emit `round`、进出各层 emit `stage`、工具前后 emit `toolCall`/`toolResult`、结束 emit `done`（约 8-10 处）。
- `AnthropicClient.java`：要在"拼完请求体"后 emit `prompt`，但**不能让 `AnthropicClient` 直接耦合 `SseEmitter`**。干净做法：给它加一个 `Consumer<PromptInfo> onPromptBuilt` 回调（跟现有 `Consumer<String> onToken` 同款）。
  - ⚠️ 现有 `chatStream(history, tools, onToken)` 已经 3 个参数了，再加回调会越来越长。建议用一个 `StreamOptions` record 把所有回调打包成一个参数传进去。

### 3.4 前端：新建 `agent-viz.html`

文件放 `src/main/resources/static/agent-viz.html`（和 `agent.html` 同目录，Spring 自动当静态资源服务）。技术栈全部 CDN、零构建：

- **Cytoscape.js**（画节点图）
- **Tailwind CSS**（沿用项目风格）
- **原生 EventSource**（浏览器内置）
- **不要** React/Vue/jQuery

这个 HTML 由 **Gemini 一次性生成**——提示词见下方第 5 节，直接复制给 Gemini，把输出存成 `agent-viz.html` 即可。

### 3.5 自测

```bash
# 把 Gemini 生成的 HTML 存好后重启
gradlew.bat bootRun
```

浏览器开 `http://localhost:8080/agent-viz.html`，输入"用 Python 写个快排"，观察：

1. 节点按顺序亮起：浏览器 → Controller → AgentService → AnthropicClient → DeepSeek
2. token 文本框实时累加**中文**（顺带验证之前的 SSE 中文编码修复仍有效）
3. prompt 卡片显示 historyLen / estimatedTokens
4. 连问 50 轮后看到压缩日志条目
5. 点"新建会话"，conversationId 变化、所有面板清空

---

## 4. GPT 画图提示词模板

### 模板 C · 6 组件协作架构图

```
你是软件架构图助手。请用 Mermaid 的 flowchart 语法画 cc-agent-java 的 6 组件协作图，要求：
- 节点：浏览器、ChatController、AgentService、AnthropicClient、DeepSeek API、ToolRegistry
- 主链路从左到右：浏览器→ChatController→AgentService→AnthropicClient→DeepSeek API
- ToolRegistry 画在 AgentService 下方，AgentService 和 ToolRegistry 之间用双向箭头表示"工具调用循环"
- 在 AgentService→AnthropicClient 的边上标注"发送 history+tools"
- DeepSeek→浏览器 用虚线标注"流式 token"
- 只输出 ```mermaid 代码块，不要解释
```

### 模板 B · 一次请求端到端时序图（含工具循环）

```
你是时序图助手。请用 Mermaid 的 sequenceDiagram 语法画"一次会调用工具的请求"，参与者：浏览器、ChatController、AgentService、AnthropicClient、ToolRegistry。要求：
1. 浏览器发消息给 ChatController，ChatController 调 AgentService
2. 用 loop 框表示"最多 10 轮"循环
3. 循环内：AgentService 调 AnthropicClient 拿模型回复
4. 用 alt 分支：如果回复只有文本 → 流式返回浏览器、结束；如果有工具调用 → AgentService 调 ToolRegistry 执行、把结果加回历史、进入下一轮
5. 只输出 ```mermaid 代码块，不要解释
```

### 模板 E · SSE 事件流时间线

```
你是时序图助手。请用 Mermaid 的 sequenceDiagram 画后端推给前端的 SSE 命名事件时间线，参与者：后端、前端。请按时间先后画后端依次 send 这些事件给前端：request → stage(enter) → prompt → round → 多个 token → toolCall → toolResult → round → 多个 token → done。在每个事件旁用 Note 标注该事件让前端更新哪个区块（如 token→更新文本框、toolCall→工具历史加一行）。
只输出 ```mermaid 代码块，不要解释
```

---

## 5. 给 Gemini 生成 `agent-viz.html` 的完整提示词（直接复制）

```
你是一名精通数据可视化与节点图动画的前端工程师，擅长用 Cytoscape.js 做交互式图形界面。

请生成一个**单一 HTML 文件**（保存为 agent-viz.html），用于实时可视化一个 Spring Boot AI Agent 的内部数据流。

## 输出硬性要求
1. 只输出一个完整的 HTML 文件，所有 CSS/JS 都内联在该文件中。
2. 仅允许通过 CDN 引入以下依赖，禁止其他外部依赖：
   - Tailwind CSS：https://cdn.tailwindcss.com
   - Cytoscape.js：https://unpkg.com/cytoscape@3.28.1/dist/cytoscape.min.js
3. 禁止使用：React/Vue/Svelte 等需要构建步骤的框架；jQuery；任何 import / require 语句；任何 npm 包。
4. 中文 UI，深色主题：背景 #0a0a0f，节点空闲色 #374151，激活色 #6366f1，错误色 #ef4444。
5. 全部 JS 代码写在末尾的单个 <script> 标签里，按功能分段并写中文注释（注释面向 C++ 转 Java 的初学者，避免堆前端术语，用大白话）。

## 数据来源
后端 SSE 端点：GET http://localhost:8080/api/chat/stream?message=<用户输入>&conversationId=<UUID>
必须用 new EventSource(url) + addEventListener 接收命名事件。
事件类型与 payload（payload 都是 JSON 字符串，需要 JSON.parse）：

- request：{conversationId, userMessage, timestamp}
- stage：{name: "controller"|"agent"|"api"|"tool"|"done", status: "enter"|"exit"}
- prompt：{system, historyLen, toolCount, estimatedTokens, compressed}
- round：{n, total}
- token：一段字符串（流式 token，本身就是 JSON 字符串，JSON.parse 还原）
- toolCall：{id, name, input}
- toolResult：{id, name, isError, contentPreview}
- compress：{beforeTokens, afterTokens, summarized}（触发记忆压缩时）
- error：{message, stage}
- done：{totalRounds, finalTokens, elapsedMs}

另外两个端点（用 fetch 调用，不走 SSE）：
- GET /api/conversations/{id} 返回该会话当前 history 数组（用于侧边面板显示完整历史）
- DELETE /api/conversations/{id} 清空会话

## 布局
页面分左右两栏，flex 横向铺满视口：
- 左侧 60%：Cytoscape 节点图
- 右侧 40%：竖向滚动的侧边面板

## 节点图规范
固定 6 个节点，preset 布局水平等距排列：
1. 浏览器（最左，作为入口指示）
2. ChatController
3. AgentService
4. AnthropicClient
5. DeepSeek API（最右）
6. ToolRegistry（位于 AgentService 下方，表示工具循环）

节点样式：圆角矩形 140×60，节点名居中，下方小字显示当前 stage 状态。
状态变化：
- 空闲：填充 #374151，无阴影
- 激活：填充渐变 #6366f1 → #818cf8，box-shadow 脉冲动画（1 秒循环）
- 错误：填充 #ef4444 持续 3 秒后恢复

边：直线带箭头，激活时变粗（4px）并加 dashed 流动动画（line-dash-offset 每 50ms 递减 4px，营造"数据流动"感）。
ToolRegistry → AgentService 是回环边，表示工具调用循环。

## 侧边面板内容（自上而下）
1. 会话栏：当前 conversationId（可复制）；"新建会话"按钮（生成新 UUID + 调 DELETE 旧的）
2. 指标卡片：第 N / 10 轮 + 预估 token 数进度条（< 100K 蓝色，100K-130K 橙色，> 130K 红色）
3. token 流文本框：max-height 200px 可滚动，token 事件实时累加渲染
4. prompt 信息卡片：折叠展开，显示最新 prompt 事件内容（system 长度、history 条数、tool 数、是否被压缩过）
5. 工具调用历史：每次 toolCall/toolResult 配对成一行，显示工具名 + 输入预览 + 输出预览（output 超过 80 字省略号）
6. 压缩日志：每次 compress 事件加一行红色标记，显示"压缩了 N 条老消息，token 从 X 降到 Y"
7. 底部输入框 + 发送按钮：Ctrl+Enter 快捷发送

## 交互细节
- 页面首次加载用 crypto.randomUUID() 生成 conversationId 存到变量（不存 localStorage）
- 发送按钮：先关掉旧的 EventSource，新建一个连到 /api/chat/stream，按事件名分别更新对应区块
- stage 事件：enter 让对应节点变激活态并启动出向边动画；exit 让节点回到空闲
- done 事件：所有节点回空闲，EventSource.close()
- error 事件：弹一个右上角红色 toast 显示 message，对应 stage 节点变红 3 秒
- 节点点击：展开下方临时面板，显示该组件在源代码里的 Java 文件名和职责（用 tooltip 即可）

## 代码组织
- 单个 <script> 标签
- 顶部定义所有常量（节点 id 数组、stage→节点 id 映射、CDN 配色）
- 然后是 Cytoscape 初始化函数
- 然后是 EventSource 建立函数
- 然后是各事件 handler 函数
- 末尾是 DOMContentLoaded 时的启动逻辑

请直接输出完整 HTML，不需要解释。
```

> ⚠️ Gemini 可能不严格守 CDN 白名单。拿到 HTML 后，搜一下 `import` / `require` / `<script src=`，确认只有 Tailwind 和 Cytoscape 两个外链，否则重新生成。

---

**上一篇** ← [01 · 跨请求记忆](01-跨请求记忆-SQLite-JPA.md)　|　**下一篇** → [03 · RAG 代码问答助手](03-RAG-代码问答助手.md)
