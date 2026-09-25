# 01 · 跨请求记忆（SQLite + JPA）

> 让 Agent 记住上下文，重启也不丢。顺带学会 **JPA / Hibernate**——这是 Java 后端求职的刚需技能，学它约等于学了一半 MySQL 开发。

---

## 1. 任务目标与背景

### 痛点

现在的代码，每次请求都在 `AgentService.run()` / `runStream()` 里新建一个 `List<Message> history`，请求一结束就被 GC 回收：

```java
// 现状：每次请求都是一张白纸
List<Message> history = new ArrayList<>();
history.add(new Message("user", userMessage));
```

结果就是——你先说"我叫张三"，再问"我叫什么"，Agent 完全不记得。**它只有"一次输入"的记忆。**

### 目标

1. **跨请求记忆**：同一个会话（用 `conversationId` 标识）的多次请求，历史能累积起来。
2. **重启不丢**：用 **SQLite** 把历史存到磁盘文件，进程重启后还在。
3. **逼近 150K 上下文窗口**：历史太长时，不是粗暴截断，而是用 **LLM 把老消息总结成一段摘要**，省 token 又不丢关键信息。

### 为什么选 SQLite + Spring Data JPA

| 维度 | 进程内 Map | SQLite + JPA（选用） |
|------|----------|--------------------|
| 重启保留历史 | ❌ | ✅ |
| 学习价值 | 低 | **高——JPA/Hibernate 是 Java 求职刚需** |
| 部署复杂度 | 无 | 一个 `.db` 文件，不用单独启数据库进程 |
| 调试 | 只能看日志 | 用 DB Browser 工具直接看表 |

不用 MySQL/Postgres：它们要起独立进程，对单机学习项目太重。不用纯内存 Map：重启就没了，学不到 ORM。**SQLite（单文件、零运维）+ JPA（学 ORM）是最佳平衡点。**

---

## 2. 要学的技术 + 基本语法

> 每个技术点三行：①一句话作用 ②最小语法 ③**C++ / 中间件类比**。

### 2.1 ORM 与 Hibernate

- **作用**：把"Java 对象"和"数据库里的一行"自动互相转换，你操作对象，它帮你生成 SQL。
- **最小语法**：你存一个对象 `repo.save(entity)`，它自动 `INSERT INTO ...`；你查 `repo.findById(1L)`，它自动 `SELECT ...` 再拼回对象。
- **C++ 类比**：像你手写过的"struct ↔ 数据库行"序列化层，但**全自动生成，一行 SQL 都不用写**。C++ 里最接近的是 ODB 这种库，但 Java 的 JPA 是行业标准、人人都用。
  - 名词关系：**JPA** 是规范（接口标准），**Hibernate** 是最流行的实现（具体干活的）。就像"STL 是标准、某编译器的实现是具体代码"。

### 2.2 实体注解 `@Entity` / `@Id` / `@GeneratedValue` / `@Column`

- **作用**：给一个普通 Java 类贴标签，告诉 Hibernate"这个类对应哪张表、哪个字段是主键、主键怎么自增"。
- **最小语法**：

```java
@Entity                      // 这个类对应一张表
@Table(name = "messages")    // 表名叫 messages
public class MessageEntity {
    @Id                                              // 这个字段是主键
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // 主键自增（交给数据库）
    private Long id;

    private String conversationId;   // 普通字段 → 普通列

    @Column(columnDefinition = "TEXT")  // 指定列类型为 TEXT（存长文本）
    private String content;
}
```

- **C++ 类比**：像给 `struct` 的字段挂"元数据标签"，类似某些序列化库要你写的宏标注（`SERIALIZE(field)`）。注解就是 Java 版的"贴在代码上、框架运行时读取"的标记。

### 2.3 `JpaRepository<T, ID>`——只写接口，不写实现

- **作用**：你只**定义一个接口**，声明想要的查询方法；Spring 启动时**自动生成实现类**。
- **最小语法**：

```java
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    // 方法名就是查询逻辑！Spring 解析方法名自动拼 SQL
    List<MessageEntity> findByConversationIdOrderBySeqAsc(String conversationId);
    long countByConversationId(String conversationId);
}
```

- **神奇之处**：方法名 `findByConversationIdOrderBySeqAsc` 会被 Spring 翻译成
  `SELECT * FROM messages WHERE conversation_id = ? ORDER BY seq ASC`。你一行实现都不用写。
- **C++ 类比**：像一个**纯虚基类**，但 `.cpp` 实现由框架在运行时用动态代理自动填好——你拿到的是一个"凭空生成"的对象。这在 C++ 里没有直接对应物（C++ 没有运行时代码生成），是 Java 框架的典型玩法，第一次见会觉得"太黑魔法了"，习惯就好。

### 2.4 `@Transactional`——事务

- **作用**：把一段操作打包成"要么全成功，要么全回滚"。
- **最小语法**：

```java
@Transactional
public void compressIfNeeded(...) {
    repo.deleteByConversationId(id);   // 删老消息
    repo.save(summaryEntity);          // 插摘要
    // 如果这里抛异常，上面的 delete 会自动回滚，不会留下"删了但没插"的脏数据
}
```

- **C++ 类比**：像 **RAII 风格的事务守卫**——进入方法时 BEGIN，正常返回时 COMMIT，抛异常时 ROLLBACK。只不过 C++ 你要自己写守卫类，Java 用一个 `@Transactional` 注解声明，框架帮你包好。中间件开发者对"ACID、事务边界"应该很熟，概念完全一致。

### 2.5 SQLite + JDBC + Dialect（方言）

- **作用**：SQLite 是单文件嵌入式数据库；JDBC 是 Java 连数据库的统一接口；Dialect（方言）告诉 Hibernate"这个数据库的 SQL 语法长什么样"。
- **最小配置**（`application.yml`）：

```yaml
spring:
  datasource:
    url: jdbc:sqlite:./data/cc-agent.db   # 数据库就是这个文件
    driver-class-name: org.sqlite.JDBC
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
```

- **C++ 类比**：你可能在客户端项目里用过 SQLite 的 **C API**（`sqlite3_open` 那套）。这里是一样的 SQLite 引擎，只是换成 Java 的 **JDBC 驱动**来连。"Dialect"这个概念是因为 Hibernate 要支持几十种数据库，每种 SQL 语法有差异，得告诉它用哪一套——类似跨平台代码里的"平台适配层"。
- **⚠️ 坑**：Hibernate 6 把 SQLite 方言挪到了单独的 `hibernate-community-dialects` 包里，依赖漏了会报 `Unable to determine dialect`。

### 2.6 记忆压缩算法（本任务的"算法核心"）

- **作用**：历史超过约 100K token 时，把"中间的老消息"丢给 LLM 总结成一段摘要，替换原文，腾出 token 空间。
- **思路**：把 history 切三段——
  1. **首条 user 消息**（任务的初始上下文）→ 不动
  2. **中间老消息** → 丢给 LLM 压成一段摘要
  3. **最近 4 轮** → 不动（保证近期对话完整）
- **C++ 类比**：像一个**带"冷热分层"的缓存淘汰策略**——热数据（最近对话）原样留着，冷数据（老对话）压缩归档。中间件里的 LRU + 压缩冷段，思路相通。
- **⚠️ 致命坑**：切割边界**不能拆散工具调用对**。如果"中间老消息"的末尾是一条孤立的 `tool_use`（模型说"我要调工具"）但它对应的 `tool_result`（工具返回）在"最近 4 轮"里，API 会报 400。所以切割时要扫描边界，把落单的 `tool_use` 也归到"最近 4 轮"一侧。

---

## 3. 实施步骤

### 3.1 加依赖（`build.gradle`）

```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
implementation 'org.xerial:sqlite-jdbc:3.45.3.0'
implementation 'org.hibernate.orm:hibernate-community-dialects:6.4.4.Final'  // SQLite 方言，别漏
```

### 3.2 加配置（`application.yml`）

```yaml
spring:
  datasource:
    url: jdbc:sqlite:./data/cc-agent.db
    driver-class-name: org.sqlite.JDBC
  jpa:
    database-platform: org.hibernate.community.dialect.SQLiteDialect
    hibernate:
      ddl-auto: update     # 首次启动自动建表，之后增量同步表结构
    show-sql: true         # 学习期打印生成的 SQL，正式部署改 false
    properties:
      hibernate.format_sql: true
```

记得手动建 `./data/` 目录（或启动时 `new File("./data").mkdirs()`）。

### 3.3 新建文件

**`model/MessageEntity.java`**（实体类。注意：JPA 要求无参构造 + getter/setter，所以**不能用 record**）：

```java
@Entity
@Table(name = "messages",
       indexes = @Index(name = "idx_conv_seq", columnList = "conversationId, seq"))
public class MessageEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String conversationId;
    private String role;                      // "user" / "assistant"
    @Column(columnDefinition = "TEXT")
    private String content;
    private String toolUseId;                 // 可空
    private Long seq;                         // 同会话内的插入顺序
    private Instant createdAt;
    // + 无参构造 + 所有字段的 getter/setter
}
```

**`repository/MessageRepository.java`**：

```java
public interface MessageRepository extends JpaRepository<MessageEntity, Long> {
    List<MessageEntity> findByConversationIdOrderBySeqAsc(String conversationId);
    @Transactional
    void deleteByConversationId(String conversationId);
    long countByConversationId(String conversationId);
}
```

**`service/ConversationStore.java`**（`@Service`，薄包装，内部把 `MessageEntity` ↔ `Message` record 互转）：

```java
@Service
public class ConversationStore {
    @Autowired private MessageRepository repo;

    public List<Message> get(String id) {
        return repo.findByConversationIdOrderBySeqAsc(id)
                   .stream().map(this::toMessage).toList();
    }
    public void append(String id, Message msg) {
        long seq = repo.countByConversationId(id);
        repo.save(toEntity(id, msg, seq));
    }
    public void clear(String id) { repo.deleteByConversationId(id); }

    // 粗略估 token：中英混排约 1 字符 0.6 token
    public int estimateTokens(List<Message> h) {
        return h.stream().mapToInt(m ->
            (int)((m.content() == null ? 0 : m.content().length()) * 0.6)).sum();
    }

    @Transactional
    public void compressIfNeeded(String id, int threshold, Compressor compressor) { /* 见 3.4 */ }
}
```

> `Compressor` 是个函数式接口（`List<Message> 老消息 -> String 摘要`），由 `AgentService` 调用时传一个 lambda 进来。这样 `ConversationStore` **不用反向依赖 `AnthropicClient`**，避免循环依赖。（C++ 类比：传一个 `std::function` 进来做回调，控制反转。）

### 3.4 压缩策略实现要点

1. 触发条件：`estimateTokens(history) > 100_000`（留 50K 给响应 + 新输入）。
2. 切三段：首条 user / 中间老消息 / 最近 4 轮。**扫描切割边界，避开落单的 `tool_use`。**
3. 把"中间老消息"喂给 `AnthropicClient.chat()`（`max_tokens=512` 限制成本），system prompt：
   `"请用一段话总结以下对话内容，保留所有关键事实、工具调用结果、用户偏好。最多 500 字。"`
4. 摘要写成单条 `MessageEntity("assistant", "[历史对话摘要] " + summary, null)`，替换中间老消息。
5. **DELETE 老消息 + INSERT 摘要必须在 `@Transactional` 里**；但"调 LLM 拿摘要"要放在事务**外**（LLM 慢，长事务会一直锁 SQLite 文件）。

### 3.5 改动现有文件

- **`model/ChatRequest.java`**：record 加一个 `String conversationId` 参数。
- **`controller/ChatController.java`**：
  - POST `/chat` 透传 `request.conversationId()`
  - GET `/chat/stream` 加 `@RequestParam(required = false) String conversationId`
  - 新增 `@DeleteMapping("/conversations/{id}")` → `store.clear(id)`
  - 新增 `@GetMapping("/conversations/{id}")` → 返回 `store.get(id)`（给可视化前端拉历史用）
- **`service/AgentService.java`**：
  - `@Autowired ConversationStore store;`
  - `new ArrayList<>()` 改为 `new ArrayList<>(store.get(conversationId))`
  - 每次 `history.add(...)` 后立即 `store.append(conversationId, msg)`
  - 每次调 API 前 `store.compressIfNeeded(...)`

### 3.6 关键不变量（必须守住）

- `conversationId` 为 null / 空时，**回退到旧行为**（每次新建 list、不写库）——保证向后兼容。
- 压缩后的 history 仍是**合法的 Anthropic 消息序列**（首条 user + 摘要 + 最近 4 轮，`tool_use_id` 配对完整）。
- 同会话的并发写**交给数据库事务**，不用 `synchronized`（SQLite 单写者锁天然序列化写操作）。

### 3.7 自测命令

```bash
# 1. 启动，看日志里有没有 "create table messages" 和 "using dialect: SQLiteDialect"
gradlew.bat bootRun

# 2. 存一句
curl -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"message\":\"我叫张三\",\"conversationId\":\"test-1\"}"

# 3. 问它（应回答"张三"）
curl -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" -d "{\"message\":\"我叫什么\",\"conversationId\":\"test-1\"}"

# 4. 【核心收益】停掉 bootRun，重启，再发步骤 3 —— 仍应回答"张三"（重启不丢历史）

# 5. 拉历史
curl http://localhost:8080/api/conversations/test-1

# 6. 清空
curl -X DELETE http://localhost:8080/api/conversations/test-1
```

再用 **DB Browser for SQLite** 打开 `./data/cc-agent.db`，肉眼看 `messages` 表的 role / content / seq 是否合理。压缩测试：循环灌 50 轮长文本，看日志里 token 估算超 100K 后是否出现"DELETE + INSERT 摘要"。

---

## 4. GPT 画图提示词模板

> 复制给 GPT/Gemini，让它出 Mermaid 图，再贴回本文档。

### 模板 D · 数据库 ER 图（画 messages 表）

```
你是数据库设计助手。请用 Mermaid 的 erDiagram 语法画 messages 表的结构，要求：
- 表名 messages，字段：id(主键,自增,bigint)、conversationId(varchar,会话id)、role(varchar)、content(text)、toolUseId(varchar,可空)、seq(bigint,会话内顺序)、createdAt(timestamp)
- 在 conversationId+seq 上标注有联合索引
- 每个字段后用注释说明用途
- 只输出 ```mermaid 代码块，不要解释
```

### 模板 B · 记忆读写时序图

```
你是时序图助手。请用 Mermaid 的 sequenceDiagram 语法画"一次带记忆的请求"的数据流，参与者：浏览器、ChatController、AgentService、ConversationStore、SQLite、AnthropicClient。要求按顺序画：
1. 浏览器带 conversationId 发请求给 ChatController
2. AgentService 调 ConversationStore.get() 从 SQLite 读历史
3. AgentService 调 AnthropicClient 把"历史+新消息"发给大模型
4. 每产生一条新消息，AgentService 调 ConversationStore.append() 写回 SQLite
5. 流式 token 返回浏览器
只输出 ```mermaid 代码块，不要解释
```

### 模板 F · 记忆压缩算法流程图

```
你是流程图助手。请用 Mermaid 的 flowchart 语法画"记忆压缩算法"的判断流程，要求：
- 入口：估算 history 的 token 数
- 判断：是否 > 100K？否 → 直接结束；是 → 进入压缩
- 压缩步骤：把 history 切成【首条user】【中间老消息】【最近4轮】三段 → 扫描边界避开落单的 tool_use → 把中间老消息发给 LLM 总结成摘要 → 在事务里 DELETE 老消息 + INSERT 摘要
- 用菱形表示判断、矩形表示动作
- 只输出 ```mermaid 代码块，不要解释
```

---

**下一篇** → [02 · 实时可视化（SSE + Cytoscape）](02-实时可视化-SSE-Cytoscape.md)：把上面这套数据流用动画画出来，眼见为实。
