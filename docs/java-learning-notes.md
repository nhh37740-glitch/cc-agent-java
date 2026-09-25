## 17. Stream API — `.map()` 和 `Map.of()`

### Stream 是什么

```
list.stream()  // 把 List 变成 Stream（流水线）
  .map(...)    // 中间操作：对每个元素做转换
  .filter(...) // 中间操作：过滤
  .toList()    // 终端操作：变回 List
```

`Stream` 不是 List，不是数组。它是一个"流水线"，让你链式处理数据。

### `.map()` 做什么

对 Stream 里的**每个元素**执行一次转换。注意：`.map()` 是 Stream 的方法，不是 Message 的方法。

```java
// stream 写法：
history.stream()
    .map(m -> Map.of("role", m.role(), "content", m.content()))
    .toList()

// 等价的 for 循环写法：
List<Map<String, String>> result = new ArrayList<>();
for (Message m : history) {
    Map<String, String> map = Map.of("role", m.role(), "content", m.content());
    result.add(map);
}
```

**m 是什么：** lambda 参数，类型由编译器从 `history`（`List<Message>`）自动推导，不用写。

### `Map.of()` 是什么

**创建一个完整的不可变 Map**，不是只添加一个键值对。

```java
Map.of()                                      // 空 Map
Map.of("a", 1, "b", 2)                        // 两个键值对
Map.of("role", "user", "content", "hello")    // 两个键值对
// 成对传入：key1, value1, key2, value2, ...
```

---

## 18. 字符编码全链路 — 从中文乱码学到的事

这一节把项目里调试 SSE 中文乱码 / 排版错乱踩过的坑串起来。从最底层的"字节"讲到最上层的"浏览器渲染"。

### 18.1 字符 vs 字节：根本区别

电脑里其实没有"字符"，只有字节（byte，8 位 0/1）。字符是人造概念，要靠**编码规则**把"中"这个抽象的字翻译成字节序列，反过来再翻译回来。

```
字符 "中"                  ← 人看的
    ↓ 编码（encode）
字节序列 [0xE4, 0xB8, 0xAD] ← 电脑存的（UTF-8 编码下）
    ↑ 解码（decode）
字符 "中"                  ← 显示出来
```

常见编码对同一个"中"字给出不同字节：

| 编码 | "中" 的字节 | 字节数 | 用在哪 |
|------|------------|-------|--------|
| UTF-8 | `E4 B8 AD` | 3 | 互联网、Linux、Mac、所有现代标准 |
| GBK | `D6 D0` | 2 | 中文版 Windows JVM 默认 |
| ISO-8859-1 | 无法表示 | 1 | HTTP/1.1 老规范的默认 |

**核心坑：用错编码解码 = 乱码**。比如 UTF-8 编出来的 `E4 B8 AD`，用 GBK 解会得到 `涓` + 半个字节（GBK 是双字节定长，3 字节按 GBK 解会拼出诡异字符）。

C++ 类比：`char[]` 是字节数组，`wchar_t[]` 才是"宽字符"，但 C++ 标准没规定 `wchar_t` 是 UTF-16 还是 UTF-32，跟 Java 不一样。Java 的 `String` 内部统一是 UTF-16，外部用 byte[] 时必须明示编码。

### 18.2 JVM 平台默认编码陷阱

下面这两行代码看着无害，**在 Windows 上跑就是定时炸弹**：

```java
// 坑 1：默认编码读字节流
BufferedReader r = new BufferedReader(
    new InputStreamReader(socket.getInputStream()));
//                       ↑ 没传 charset 参数

// 坑 2：默认编码把字节数组转字符串
String text = new String(httpResponse.getBytes());
//                       ↑ 没传 charset 参数

// 坑 3：默认编码把字符串转字节
byte[] bytes = "你好".getBytes();
//                  ↑ 没传 charset 参数
```

**JVM 默认编码 = 系统 region 决定**：

| 系统 | JVM 默认编码 |
|------|------------|
| 中文版 Windows | GBK（或 MS936） |
| Linux | UTF-8 |
| macOS | UTF-8 |

经典悲剧："在我 Mac 上能跑，部署到同事 Windows 机器就乱码" / "本地 Windows 写代码好的，docker 容器（Linux UTF-8）跑就好了 — 误以为修了，其实没修"。

**铁律**：所有涉及 byte ↔ String 的 API，**永远显式传 `StandardCharsets.UTF_8`**。Java 9 起还加了不带 charset 重载的废弃警告，新代码不应该出现。

本项目踩坑实例 — `AnthropicClient.java` 一开始这样写：

```java
BufferedReader reader = new BufferedReader(
    new InputStreamReader(response.body()));  // ← 默认 GBK 解 DeepSeek 的 UTF-8 流
```

DeepSeek API 返回的字节流是 UTF-8 编码的 JSON，被 GBK 解码后中文全乱。修复就是加一个参数：

```java
import java.nio.charset.StandardCharsets;

BufferedReader reader = new BufferedReader(
    new InputStreamReader(response.body(), StandardCharsets.UTF_8));
```

### 18.3 HTTP Content-Type 里的 charset 参数

HTTP 响应头里的 `Content-Type` 通常长这样：

```
Content-Type: text/html;charset=UTF-8
              ↑         ↑
              MIME类型   告诉浏览器按 UTF-8 解码响应字节
```

浏览器拿到响应字节后，**看 charset 决定怎么解码成字符**。

`text/*` 类型如果没声明 charset 会怎样？

- HTTP/1.1 老规范（RFC 2616）：默认 ISO-8859-1（latin-1，单字节，连"中"都表示不了）
- HTTP/1.1 新规范（RFC 7231）：移除了默认值，但 Tomcat 这类服务器仍按 ISO-8859-1 兜底
- 浏览器：现代浏览器对 HTML 会嗅探 `<meta charset>` 自动纠正，但对 SSE/JSON 就老老实实按服务端声明的来

**陷阱**：写 Spring Boot 的 `@GetMapping` 时不指定 `produces`，Spring 会发出 `Content-Type: text/event-stream`（没 charset），Tomcat 实际写字节走 ISO-8859-1 → 中文全坏。

修复 — 显式声明：

```java
@GetMapping(value = "/chat/stream", produces = "text/event-stream;charset=UTF-8")
public SseEmitter chatStream(...) { ... }
```

C++ 类比：HTTP charset 就像 SQLite `PRAGMA encoding` — 字节存进去之前必须先告诉对方"按什么编码读"，否则双方就鸡同鸭讲。

### 18.4 SSE 协议：data: 字段不允许含换行

SSE（Server-Sent Events）是 W3C 标准的服务器推送协议。报文长这样：

```
data: 第一条消息

data: 第二条消息
event: tool_call
data: {"name":"file_read"}

```

格式规则：
- 每行是 `字段名: 值`
- **事件**之间用空行（`\n\n`）分隔
- 同一事件内可以有多个 `data:` 行，浏览器把它们用 `\n` 连接

**坑就在最后一条**：如果服务端要发的 value 里本身有真换行（比如 token = `\n# 标题\n`），SSE 协议不允许直接放进 `data:` 字段，Tomcat 会按规范**自动拆成多行**：

```
data: 
data: # 标题
data: 

```

浏览器原生 `EventSource` 收到这串会自动把多行 data 用 `\n` 拼回去 → 拿到正确字符串。

但本项目前端用的是 `fetch` + `getReader()` 手动按行解析（为了能 abort），**没实现这个"多行 data 累积"逻辑**，每行 `data:` 都独立处理，**换行就丢了**。所以 LLM 输出的 markdown 标题、代码块、列表全连成一坨。

### 18.5 三种解决方案对比 — 为什么 JSON 编码最干净

要解决"token 里的换行被 SSE 协议吃掉"，思路是**让 token 在传输时不含真换行**。三种做法：

**A. Base64 编码**（项目第一版的屎山）

```java
// 后端
String safe = Base64.getEncoder().encodeToString(token.getBytes(UTF_8));
emitter.send(safe);
```

```js
// 前端
let bytes = Uint8Array.from(atob(dataContent), c => c.charCodeAt(0));
let decoded = new TextDecoder('utf-8').decode(bytes);
```

缺点：解码代码长 + Network 面板看到的 SSE 都是无意义的 ASCII 串，调试时不知道发的啥。

**B. 手工 `\n → \\n` 转义**

```java
String escaped = token
    .replace("\\", "\\\\")  // 必须先转 backslash，否则后面的会被二次转义
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t");
```

缺点：转义顺序极易写错；漏处理 `"` 之类的特殊字符；前端反转义也要镜像写一遍。

**C. JSON 编码**（最终方案）

```java
// 后端（一行）
emitter.send(objectMapper.writeValueAsString(token));
```

```js
// 前端（一行）
const dataContent = JSON.parse(trimmedLine.slice(5).trim());
```

为什么这个最优：
- `writeValueAsString("hello\n你好")` → 输出 `"hello\n你好"`（带引号的 JSON 字符串字面量）
- JSON 规范本身就要求字符串里不能含真换行，所有控制字符自动转义
- 标准库覆盖**所有**特殊字符（`\n \r \t \" \\ \uXXXX`），不会漏
- 浏览器 F12 看到的 SSE 内容是带引号的可读字符串，肉眼可读
- 后端一行、前端一行，对称、不留死角

C++ 类比：相当于用 `json::serialize` 而不是手写 `escape_string` —— 标准库已经把所有坑都填好了。

### 18.6 全链路心智图

修复后完整的数据流，标注每一处编码 boundary：

```
DeepSeek API（UTF-8 JSON SSE 流）
    │
    │ 字节流
    ▼
HttpClient.send() 收到 InputStream（裸字节）
    │
    │ ★ 编码边界 1：字节 → 字符
    │   AnthropicClient: new InputStreamReader(stream, StandardCharsets.UTF_8)
    │   不传 charset 在 Windows 上会用 GBK 解 UTF-8 字节 → 乱码
    ▼
BufferedReader.readLine() 拿到 String（Java 内部 UTF-16）
    │
    │ JsonNode.get("text").asText() 拿到 token（含真换行 \n）
    ▼
AgentService 的 onToken 回调
    │
    │ ★ 编码边界 2：原始 token → SSE 安全的单行字符串
    │   objectMapper.writeValueAsString(token) → "hello\n你好"（JSON 字面量）
    │   不做这步：真换行被 Tomcat 按 SSE 规范拆成多个 data: 行 → 前端丢换行
    ▼
emitter.send(jsonStr)
    │
    │ ★ 编码边界 3：字符 → 响应字节
    │   ChatController @GetMapping(produces=...;charset=UTF-8)
    │   不声明：Tomcat 默认 ISO-8859-1 → 中文字节被截断
    ▼
Tomcat 写 HTTP 响应字节
    │
    │ Content-Type: text/event-stream;charset=UTF-8
    │ Transfer-Encoding: chunked
    ▼
浏览器 fetch().body 拿到 ReadableStream<Uint8Array>（裸字节）
    │
    │ ★ 编码边界 4：字节 → 字符
    │   new TextDecoder('utf-8').decode(chunk)
    │   不指定 'utf-8'：默认就是 utf-8，但显式写更清晰
    ▼
按 \n 拆行，取 data: 之后内容
    │
    │ ★ 编码边界 5：JSON 字面量 → 原始 token
    │   JSON.parse('"hello\n你好"') → "hello\n你好"（含真换行）
    ▼
accumulatedReply += token → 真换行保留 → markdown 正确渲染
```

**5 个编码边界，每一个出错都会乱码或排版崩。**

### 18.7 调试套路：怎么定位坏在哪一步

下次再出乱码，**按顺序自查**：

1. **后端拿到上游字节后第一次转字符串时编码对不对？**
   - 看 `InputStreamReader` / `new String(byte[])` 有没有显式 UTF-8
   - 在日志里打 `response.body().substring(0, 200)` 看头几个字节是不是合理的 UTF-8 序列

2. **HTTP 响应头 Content-Type 有没有 charset？**
   - F12 → Network → 找请求 → Response Headers → 找 Content-Type
   - 没 charset 就是后端 produces 没写

3. **响应体在 Network 面板里看，是不是浏览器能正确显示中文？**
   - 如果 Network 视图都已经是乱码，说明问题在后端或网络层
   - 如果 Network 视图正常但页面渲染乱码，说明问题在前端 JS

4. **如果是 SSE，EventStream 视图里每个事件的 data 字段长啥样？**
   - 看到的是原文还是 Base64/JSON 串？
   - 多个连续 `data:` 行说明 token 里有真换行被拆了

5. **前端解码字节用的 TextDecoder 编码对不对？**
   - 默认是 utf-8，除非业务接的是 GBK 老系统才需要改

### 18.8 经验法则（贴在墙上的那种）

1. **byte ↔ String 永远显式传 `StandardCharsets.UTF_8`** — 别信平台默认
2. **HTTP 响应永远在 Content-Type 里声明 charset** — 别让客户端猜
3. **任何"文本协议"传任意字符串前先想：协议有没有保留字符？** — SSE 怕换行，CSV 怕逗号，URL 怕 `&` `=`
4. **要转义就用标准库** — `JSON.stringify`、`URLEncoder.encode`、`Base64.getEncoder()`，别手写 `.replace().replace().replace()`
5. **乱码出现时先看 HTTP 头**，不要急着改代码 — 90% 的乱码是声明错了，不是代码逻辑错了

---

## 19. Message、工具调用、usage token 的关系

### 19.1 Message 是什么

一条 `Message` 由两部分组成：

```text
role + contentBlocks
```

`role` 表示这条消息是谁发的：

```text
user
assistant
```

`contentBlocks` 表示这条消息里面具体有什么内容。

普通文本是 `text` block：

```text
role = user
contentBlocks = text: "读一下 a.txt"
```

工具调用是 assistant message 里的 `tool_use` block：

```text
role = assistant
contentBlocks = tool_use: read_file(path=a.txt)
```

工具执行结果是 user message 里的 `tool_result` block：

```text
role = user
contentBlocks = tool_result: "文件内容是..."
```

所以工具调用不是单独的 message 类型。它只是 `Message.contentBlocks` 里面的一种内容块。

### 19.2 工具调用流程

一次带工具的对话可能长这样：

```text
1. user message
   text: "读一下 a.txt"

2. assistant message
   tool_use: read_file(path=a.txt)

3. user message
   tool_result: "文件内容是..."

4. assistant message
   text: "这个文件主要讲..."
```

第一次 API 调用：

```text
发送：1
返回：2
```

第二次 API 调用：

```text
发送：1 + 2 + 3
返回：4
```

### 19.3 usage token 是什么

`usage` 是 API 返回的 token 用量统计。

非流式响应里，它在最终 JSON 里。

流式响应里，它在 `HttpResponse<InputStream>` 读到的 SSE `data:` JSON 里，常见事件是：

```text
event: message_start
data: {...}

event: message_delta
data: {"type":"message_delta","usage":{...}}
```

`usage.input_tokens` 表示本次 API 输入用了多少 token，包含：

```text
system prompt
+ tools
+ 历史 messages
+ 本轮 user message
+ tool_result
```

`usage.output_tokens` 表示模型本轮输出用了多少 token。

### 19.4 input_tokens 和 output_tokens 的例子

第一次 API：

```text
发送：1
返回：2

usage.input_tokens  = 1 + system/tools
usage.output_tokens = 2
```

第二次 API：

```text
发送：1 + 2 + 3
返回：4

usage.input_tokens  = 1 + 2 + 3 + system/tools
usage.output_tokens = 4
```

注意：

```text
input_tokens 不包含模型这次返回的内容。
output_tokens 才是模型这次返回的内容。
```
