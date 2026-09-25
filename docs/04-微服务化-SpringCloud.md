# 04 · 微服务化（Spring Cloud）

> 把单体拆成 4 个服务，学 **Nacos / Gateway / Feign / 熔断 / 链路追踪**——Spring Cloud 全家桶是日本 Java 岗（SIer、楽天、リクルート、メルカリ）筛简历的硬指标。这是**终极阶段**，等 01～03 全做完再动手。

---

## 1. 任务目标与背景

### 先说大实话

**业务上完全不需要拆。** 当前调用链 `ChatController → AgentService → (AnthropicClient + ToolRegistry → 工具)`，单进程 15 个 Java 文件跑得很舒服。强行拆会引入：

- 内存调用 1μs → 网络调用 1-10ms
- 序列化开销
- 运维复杂度爆炸（docker-compose 同时跑 6 个进程）
- 调试地狱（一个请求要看 4 个服务的日志）

这是**典型的"为简历拆服务"**。但——日本企业对 Spring Cloud 招聘需求是真实的，简历上"会 Spring Cloud 全家桶"在**筛简历阶段就是个过滤器**。

### 正确定位（面试照实说）

把它定位成**学习项目的第二阶段**，README 里诚实写：**"业务上单体够用，本次拆分用于学习 Spring Cloud 技术栈。"** 面试被问"为什么拆"就照实答："业务不需要，但拆完帮我真正理解了服务边界、熔断、链路追踪的意义。"——这比假装"我们业务真需要"得分高得多。

### 为什么排最后

拆微服务是**架构级重构**。必须等单体里记忆（01）、可视化（02）、RAG（03）全做完，把"数据存哪、边界在哪"理清楚再拆。**反过来先拆服务再加功能 = 自找麻烦**（每加一个功能都要决定放哪个服务、怎么跨服务调）。这叫**演化式架构**。

---

## 2. 要学的技术 + 基本语法

### 2.1 服务注册发现（Nacos）

- **作用**：每个服务启动时把自己的地址"上报"到注册中心；别的服务想调用时，按**服务名**去注册中心查地址（而不是写死 IP）。
- **最小语法**：

```yaml
spring:
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848   # 注册中心地址
  application:
    name: chat-service                # 我注册的服务名
```

- **C++ 类比**：就是 **etcd / Consul + 客户端负载均衡**。中间件里"服务上报心跳、调用方查服务列表、挑一个实例调"那一套，概念完全一致。Nacos 还能**兼任配置中心**（省一个组件）。

### 2.2 API 网关（Spring Cloud Gateway）

- **作用**：所有外部请求的**统一入口**，负责路由（按路径转发到不同服务）、限流、鉴权。
- **最小语法**：

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: chat
          uri: lb://chat-service        # lb = 走注册中心负载均衡
          predicates:
            - Path=/api/chat/**          # 匹配这个路径就转给 chat-service
```

- **C++ 类比**：像 **Nginx 反向代理**，但路由规则用 Java 写、能直接读注册中心动态拿后端地址。中间件做过 API 网关 / 流量入口的话，这就是 Java 版。

### 2.3 OpenFeign（声明式远程调用）

- **作用**：跨服务调用 HTTP 接口时，你**只写一个接口声明**，Feign 自动生成"发 HTTP 请求 + 解析响应"的代码。
- **最小语法**：

```java
@FeignClient(name = "tool-service")     // 调用名为 tool-service 的服务
interface ToolFeignClient {
    @PostMapping("/tools/execute")
    ToolResult execute(@RequestParam String name, @RequestBody Map<String,Object> input);
}
// 用起来就像调本地方法：toolSvc.execute("file_read", input)
```

- **C++ 类比**：像 **gRPC 的 stub**——你定义接口，框架生成客户端代码，调用远程像调本地。区别是 Feign 基于注解 + REST/JSON，gRPC 基于 IDL + protobuf。

### 2.4 Resilience4j（熔断 / 重试 / 限流）

- **作用**：远程调用可能失败（对方挂了、超时）。熔断器在"对方频繁失败"时**自动跳闸**，直接走降级逻辑（fallback），不让故障层层传染。
- **最小语法**：

```java
@FeignClient(name = "llm-service", fallback = LlmFallback.class)  // 挂了就用 fallback
interface LlmFeignClient { ... }
```

- **C++ 类比**：就是中间件里经典的**熔断器模式**（Circuit Breaker，Hystrix 那一套，现在换 Resilience4j 因为 Hystrix 停更了）。"半开 / 打开 / 关闭"三态，做过高可用中间件的话很熟。

### 2.5 Micrometer Tracing + Zipkin（链路追踪）

- **作用**：一个请求跨了好几个服务，给它一个全局 **traceId**，把各服务的日志按 traceId **串成一条调用链**，在 Zipkin 界面上看完整路径和每段耗时。
- **C++ 类比**：就是 **OpenTelemetry / 分布式 tracing**。中间件埋点、traceId 透传、span 父子关系那套，概念一致。Agent 的"多轮工具调用"在 Zipkin 上会显示成一条漂亮的 span 链，demo 效果震撼。
- **⚠️ 版本坑**：Spring Boot 3.x 时代旧的 Sleuth 已被 **Micrometer Tracing** 取代，别照老教程用 Sleuth。

### 2.6 SSE 跨服务透传（真正的技术难点）

- **问题**：DeepSeek 的流式响应是 SSE 长连接，但 **Feign 是同步阻塞的，不支持流式**。
- **方案**：`llm-service` 内部用 `WebClient`（响应式 HTTP 客户端）直连 DeepSeek，`chat-service` → `llm-service` 之间也用 SSE 透传，不走 Feign。
- **C++ 类比**：像区分"一问一答的 RPC"和"流式数据管道"——后者得用支持背压 / 流的传输层，不能用阻塞式 stub 硬塞。**这是面试可以深聊的真实技术决策。**

---

## 3. 实施步骤

### 3.1 服务划分（最小可学版：4 业务服务 + Nacos）

| 服务 | 职责 | 从当前代码迁出 |
|---|---|---|
| **gateway** | 统一入口、路由、限流 | 新建 |
| **chat-service** | ChatController + AgentService 主循环 | 直接迁 |
| **tool-service** | ToolRegistry + 所有 Tool + PathValidator | 直接迁 |
| **llm-service** | AnthropicClient（封装 DeepSeek） | 直接迁 |
| **Nacos** | 注册中心 + 配置中心 | 新基础设施 |

这套覆盖 Spring Cloud 90% 核心知识点，2-3 周可完成。**别按"每个工具一个服务"拆**——工具是无状态函数，没有独立部署需求，留在 tool-service 里就好。

### 3.2 技术栈选型（直接抄）

| 组件 | 选 | 一句话理由 |
|---|---|---|
| 注册 + 配置中心 | **Nacos** | 一个组件干两件事，比 Eureka 现代 |
| API 网关 | **Spring Cloud Gateway** | Zuul 已死；基于 WebFlux 顺带学响应式 |
| 服务间调用 | **OpenFeign** | 声明式、简历友好（流式调用单独用 WebClient） |
| 熔断限流 | **Resilience4j** | Hystrix 停更，官方推荐 |
| 链路追踪 | **Micrometer Tracing + Zipkin** | Boot 3.x 新栈，会的人少 = 加分 |
| 容器化 | **Docker Compose** | 别上 K8s，学习项目过重 |

### 3.3 三阶段演化（别一步到位）

- **阶段 0（前提）**：01～03 全做完。
- **阶段 1（1 周）：基础设施落地**——起 Nacos、gateway；当前单体改名 chat-service 注册上去；外部请求经 Gateway 转发，对外 API 不变。
- **阶段 2（1 周）：垂直切分**——抽出 llm-service（搬 AnthropicClient）、tool-service（搬 ToolRegistry + Tools + PathValidator）；chat-service 用 Feign 调它们；里程碑：3 个服务在 docker-compose 里独立跑。
- **阶段 3（1 周）：可靠性 + 可观测**——加 Resilience4j 熔断（chat→llm 最关键，DeepSeek 抖动是常态）；接 Micrometer Tracing + Zipkin；里程碑：杀掉 llm-service 时 chat-service 优雅降级，Zipkin 能看完整链路。

### 3.4 AgentService 拆分后长什么样

```java
// chat-service 内（保留主循环骨架，本地调用变远程调用）
@Service
public class AgentService {
    @Autowired LlmFeignClient llm;
    @Autowired ToolFeignClient toolSvc;

    @NewSpan("agent.run")    // 让 Zipkin 把这段串进链路
    public void runStream(String userMsg, SseEmitter emitter) {
        var history = new ArrayList<>(List.of(new Message("user", userMsg)));
        var toolDefs = toolSvc.listTools();

        for (int round = 0; round < 10; round++) {
            // ⚠️ SSE 流式跨服务透传：Feign 不支持，用 WebClient
            var apiResp = llm.chatStream(history, toolDefs, emitter::send);
            if (apiResp.toolCalls().isEmpty()) { emitter.complete(); return; }

            for (ToolCall tc : apiResp.toolCalls()) {
                var result = toolSvc.execute(tc.name(), tc.input());  // 熔断保护，挂了走 fallback
                history.add(new Message("user", result.content(), tc.id()));
            }
        }
    }
}
```

**关键变化**：本地方法调用 → 网络调用、`@NewSpan` 串链路、远程调用都要 fallback、SSE 跨服务透传是难点。

### 3.5 求职收益（简历能写什么）

按含金量排序：

1. "基于 Spring Cloud 实现 LLM Agent 微服务系统，OpenFeign 服务间调用，Nacos 注册发现，Resilience4j 熔断降级"——一句话覆盖筛简历关键词。
2. "Micrometer Tracing + Zipkin 实现跨服务链路追踪，可视化 Agent 多轮工具调用链"——有真实场景的可观测性经验。
3. "跨服务透传 SSE 流式响应，解决 Feign 不支持流式的问题"——真实技术决策，面试深聊点。

**面试必问**：为什么拆（诚实答）、Feign 怎么传 SSE（准备好）、Nacos vs Eureka、Gateway 过滤器链、熔断与限流的区别（八股背熟）。

---

## 4. GPT 画图提示词模板

### 模板 C · 4 服务部署架构图

```
你是软件架构图助手。请用 Mermaid 的 flowchart 语法画 Spring Cloud 微服务部署架构，要求：
- 浏览器 → API Gateway（统一入口）
- Gateway 转发到 chat-service
- chat-service 用 Feign 调 tool-service 和 llm-service
- llm-service 用 WebClient 连外部 DeepSeek API（画在最右，虚线表示外部）
- 所有内部服务都连到 Nacos（注册+配置中心），用虚线表示"注册/发现"
- 用 subgraph 把内部服务框成"docker-compose 集群"
- 只输出 ```mermaid 代码块，不要解释
```

### 模板 B · 跨服务调用时序图

```
你是时序图助手。请用 Mermaid 的 sequenceDiagram 画一次跨服务请求，参与者：浏览器、Gateway、chat-service、llm-service、tool-service、DeepSeek。要求：
1. 浏览器经 Gateway 把请求路由到 chat-service
2. 用 loop 表示 Agent 最多 10 轮循环
3. chat-service 经 SSE 透传调 llm-service，llm-service 用 WebClient 连 DeepSeek 拿流式回复
4. 如果模型要调工具，chat-service 用 Feign 调 tool-service 执行
5. 用 Note 标注"Feign=同步RPC，SSE透传=流式不走Feign"
只输出 ```mermaid 代码块，不要解释
```

### 模板 G · Zipkin span 链路示意

```
你是链路追踪图助手。请用 Mermaid 的 gantt 语法画一条 Zipkin trace 的 span 时间线，模拟一次会调用 2 轮工具的 Agent 请求，要求：
- 一个根 span：gateway 收请求（最长，覆盖全程）
- 子 span 依次：chat-service.runStream、llm-service 第1轮调用、tool-service 执行工具A、llm-service 第2轮调用、tool-service 执行工具B、llm-service 最终生成
- 每个 span 给个示意耗时（ms），体现父子嵌套和先后顺序
只输出 ```mermaid 代码块，不要解释
```

---

**上一篇** ← [03 · RAG 代码问答助手](03-RAG-代码问答助手.md)　|　**回到** [00 · 总览与路线图](00-总览与路线图.md)
