# 03 · RAG 代码问答助手（LangChain4j）

> 让 Agent 能回答"我这个项目自己的代码是怎么写的"。把项目源码 + 文档灌进检索库，模型基于检索到的真实代码片段作答。顺带学会 **Embedding / 向量检索 / Chunking**——RAG 是当下 AI 应用最值钱的技能之一。

---

## 1. 任务目标与背景

### 什么是 RAG

RAG = Retrieval-Augmented Generation（检索增强生成）。大白话：**先去知识库里搜出相关资料，把资料塞进 prompt，再让大模型基于资料回答**。这样模型能回答它"没被训练过"的私有内容（比如你自己的代码），还能给出处。

### 这个任务做什么（自指设计）

把 cc-agent-java **自己的源码 + docs + CLAUDE.md** 灌进检索库，让 Agent 能回答关于自己的问题：

- "ToolRegistry 是怎么自动收集工具的？"
- "为什么 SSE 要做 JSON 包装？"
- "我要新增一个工具应该改哪里？"
- "PathValidator 怎么防止路径越狱？"

模型基于检索到的源码片段作答，还附带文件路径引用（如 `FileReadTool.java:42`）。

### 为什么这个场景最好

- **数据现成**：源码 + docs/ + CLAUDE.md 都在手边，不用爬数据。
- **自指设计**：项目讲自己的代码——面试 demo 时可以现场问任何代码细节，效果震撼。
- **复用 01 的存储**、**复用现有 Agent + Tool 架构**（作为一个新工具接入，不动主循环）。

### 为什么不用 Dify

Dify 把 chunking / embedding / 向量库全包了，**你学不到东西**，简历只能写"会用 Dify"，面试问"chunking 怎么做"就答不上。所以**自己用 LangChain4j 实现**——学习价值最高，代码增量也就 3 个新文件 + 2 行依赖。

> 这一篇在**单体内增量**，不动现有架构，所以排在"拆微服务（04）"之前做。

---

## 2. 要学的技术 + 基本语法

### 2.1 Embedding（向量化）

- **作用**：把一段文本变成一个**定长的浮点数向量**（比如 512 个 float），这个向量是文本的"语义指纹"。
- **关键性质**：**语义相似的文本，向量距离也近**。"怎么读文件"和"file read 实现"会得到相近的向量。
- **C++ 类比**：像 hash——都是"文本 → 定长输出"。**但有个根本区别**：普通 hash 一个字符变了输出就面目全非（不保留相似性）；embedding 专门设计成"相似输入 → 相近输出"。所以它能做"语义搜索"，hash 不能。中间件做过相似度去重 / 局部敏感哈希（LSH）的话，思路是相通的。

### 2.2 向量检索 topK

- **作用**：给一个查询（query）的向量，在库里找出**最相似的前 K 个**片段。
- **最小语法**：

```java
var queryVec = embeddingModel.embed("怎么读文件").content();
var matches = store.findRelevant(queryVec, 5);  // 取最相似的 5 个
```

- **原理**：算 query 向量和库里每个向量的**余弦相似度**，排序取前 K。
- **C++ 类比**：就是**暴力 KNN**（K 近邻）——逐个算距离、排序、取前 K。数据量大了（百万级）才需要换 FAISS / 专用向量库做近似最近邻。学习项目数据小，暴力扫就够。

### 2.3 Chunking（切块）

- **作用**：大文档不能整篇 embed（太长、检索不精准），要**切成小段**再分别 embed。
- **策略**：Java 文件按**类 / 方法**边界切，Markdown 按**标题**切；每个块带上元数据（文件路径 + 行号）。
- **C++ 类比**：像把一个大 `.cpp` 文件**按函数边界切成片段**再分别建索引——你不会把整个文件当一个搜索单元，而是按语义边界（函数 / 类）切。切得好不好直接决定检索质量。

### 2.4 InMemoryEmbeddingStore（向量库）

- **作用**：存所有"片段向量 + 原文"的地方，支持 `findRelevant` 检索。
- **选型**：学习项目数据量小（项目自身代码 < 100 个块），**用内存版就够**，启动时序列化到 `data/embeddings.json` 缓存，下次启动直接 load 不用重算。
- **C++ 类比**：就是一个进程内的 `std::vector<std::pair<vector<float>, string>>` + 相似度扫描函数。**别上 pgvector / Weaviate / Chroma**——那些要起独立服务，对单机学习项目是运维灾难。

### 2.5 LangChain4j

- **作用**：Java 版的 LLM 应用框架。我们**只用它两个模块**：`embedding`（本地向量模型）+ `document-parser`（解析文档）。
- **关键**：**不**用它的 ChatModel（我们已经有 `AnthropicClient` 调 DeepSeek 了），避免重复 / 冲突。
- **C++ 类比**：像一个"AI 应用的 boost 库"——挑你要的模块用，不用全家桶。

### 2.6 零侵入接入（设计上最妙的一点）

- **作用**：RAG 不改 Agent 主循环，而是**做成一个新工具**。`CodeSearchTool implements AgentTool`，靠现有的"自动收集所有 AgentTool"机制注册，模型看到工具定义后**自己决定**要不要调用检索。
- **C++ 类比**：像往一个**插件系统**里丢一个新插件——主程序一行不改，启动扫描时自动发现并加载。现有 `ToolRegistry` 就是这个插件管理器。

---

## 3. 实施步骤

### 3.1 加依赖（`build.gradle` 加 2 行）

```gradle
implementation 'dev.langchain4j:langchain4j:0.36.2'
implementation 'dev.langchain4j:langchain4j-embeddings-bge-small-zh:0.36.2'  // 本地中文 embedding 模型
```

> 用**本地 bge-small-zh onnx 模型**（约 100MB，启动加载）：DeepSeek 没有 embedding API，OpenAI 要钱要翻墙，本地模型**零成本、零延迟、离线可用**，面试讲"本地模型"还加分。

### 3.2 新建 `service/RagService.java`

```java
@Service
public class RagService {
    private EmbeddingModel embeddingModel;        // 本地 bge onnx
    private EmbeddingStore<TextSegment> store;    // InMemoryEmbeddingStore

    @PostConstruct   // Spring 创建完这个 Bean 后自动调一次（C++ 类比：构造后的 init 钩子）
    void init() {
        // 1. 扫描源码 + docs → 按类/方法、按标题切块（带 file_path + 行号 元数据）
        // 2. 每块 embed，存进 store
        // 3. 序列化到 data/embeddings.json，下次启动直接 load 不重算
    }

    public List<TextSegment> search(String query, int topK) {
        var queryEmbedding = embeddingModel.embed(query).content();
        return store.findRelevant(queryEmbedding, topK).stream()
                    .map(EmbeddingMatch::embedded).toList();
    }
}
```

### 3.3 新建 `tool/CodeSearchTool.java`

```java
@Component
public class CodeSearchTool implements AgentTool {
    @Autowired RagService rag;

    public String getName() { return "code_search"; }
    public String getDescription() {
        return "搜索本项目的源码和文档，回答关于项目实现细节的问题。输入参数 query 是要搜索的关键词或自然语言问题。";
    }
    public String execute(Map<String, Object> input) {
        var results = rag.search((String) input.get("query"), 5);
        // 拼成"文件路径 + 行号 + 代码片段"返回给模型
        return results.stream()
            .map(seg -> "## " + seg.metadata().get("file_path") + ":"
                       + seg.metadata().get("start_line") + "\n```\n" + seg.text() + "\n```")
            .collect(Collectors.joining("\n\n"));
    }
}
```

### 3.4 就这样——主循环一行不改

`AgentService`、`AnthropicClient`、`ToolRegistry` **完全不动**。Spring 启动时自动把 `CodeSearchTool`（`@Component implements AgentTool`）收进工具列表，模型看到 `code_search` 工具定义后会自主决定调用。

> 彩蛋：在 [02](02-实时可视化-SSE-Cytoscape.md) 的 Cytoscape 图里加一个 `RagService` 节点 + `code_search` 工具节点，就能把 RAG 调用也画进数据流——自指可视化叠加自指 RAG。

### 3.5 关键不变量 / 注意点

- **Chunk 带元数据**：每个片段必须带 `file_path` + `start_line`，否则回答没法给出处。
- **缓存机制**：embed 一次很慢，务必序列化缓存，避免每次启动重算。
- **检索结果拼接**有大小限制：topK=5、每块别太大，否则塞爆 prompt。

### 3.6 自测

1. 启动后看日志：embedding 模型加载成功、建库完成、`data/embeddings.json` 生成。
2. 问 Agent："ToolRegistry 是怎么自动收集工具的？"——观察它是否调用了 `code_search` 工具，回答里是否带 `ToolRegistry.java` 的真实代码片段和路径。
3. 自己写 10 个标准问答，统计命中率（hit rate）——这是评估 RAG 效果的标准做法，面试会问。

---

## 4. GPT 画图提示词模板

### 模板 F · 离线建库管线图

```
你是流程图助手。请用 Mermaid 的 flowchart 语法画 RAG"离线建库"的处理管线，要求按顺序：
扫描源码和docs目录 → 按语义边界切块(Java按类/方法,Markdown按标题) → 每块附加元数据(文件路径+行号) → 用本地BGE模型做Embedding → 存入InMemoryEmbeddingStore → 序列化到 data/embeddings.json 缓存
用矩形表示处理步骤，最后一步用不同颜色标注（表示缓存产物）。
只输出 ```mermaid 代码块，不要解释
```

### 模板 B · 在线检索问答数据流图

```
你是时序图助手。请用 Mermaid 的 sequenceDiagram 画 RAG 在线问答流程，参与者：用户、AgentService、大模型、CodeSearchTool、RagService、向量库。要求：
1. 用户提问，AgentService 把问题+工具列表发给大模型
2. 大模型决定调用 code_search 工具，返回工具调用请求
3. AgentService 调 CodeSearchTool → RagService → 向量库做 topK 检索，拿到代码片段
4. AgentService 把检索结果作为工具结果加回历史，再次发给大模型
5. 大模型基于真实代码片段生成带文件路径引用的回答，返回用户
只输出 ```mermaid 代码块，不要解释
```

### 模板 C · CodeSearchTool 挂进现有 Agent 循环

```
你是架构图助手。请用 Mermaid 的 flowchart 画"新工具零侵入接入"的结构，要求：
- 已有组件用一种颜色：AgentService、ToolRegistry、FileReadTool、FileWriteTool
- 新增组件用高亮色：CodeSearchTool、RagService
- 画出 ToolRegistry 通过"自动收集所有 AgentTool 实现"把 CodeSearchTool 和其他工具一起收进来
- CodeSearchTool 依赖 RagService，RagService 内含 EmbeddingModel 和 EmbeddingStore
- 标注"AgentService 和 ToolRegistry 一行代码都不用改"
只输出 ```mermaid 代码块，不要解释
```

---

**上一篇** ← [02 · 实时可视化](02-实时可视化-SSE-Cytoscape.md)　|　**下一篇** → [04 · 微服务化（Spring Cloud）](04-微服务化-SpringCloud.md)
