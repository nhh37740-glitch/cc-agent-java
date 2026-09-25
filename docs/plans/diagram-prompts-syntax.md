# Java 目录结构和语法图解 Prompt

---

## Prompt 1：Java 项目目录结构全景图

```
画一个图解，解释 Java Spring Boot 项目的目录结构。

用树形图 + 注释的方式，解释每个目录和文件的作用。

要求：
1. 左边是目录树，右边是对应的注释解释
2. 用连接线把目录/文件连到它的解释
3. 分四个层级标注

结构如下：

cc-agent-java/                          ← 项目根目录
│
├── build.gradle                         ← [构建配置] 类似 CMakeLists.txt
│                                             声明依赖、Java 版本、插件
│
├── settings.gradle                      ← [项目名称] 就一行：rootProject.name
│
├── jdk17/                               ← [自带 JDK] 不需要装 Java 环境
│   └── jdk-17.0.14+7/
│       └── bin/java.exe                 ← [JVM 程序] 执行 .class 文件
│
├── gradlew.bat / gradlew                ← [Gradle Wrapper] 不需要装 Gradle
│
├── run.bat                              ← [启动脚本] 双击运行项目
│
└── src/                                 ← [源代码根目录]
    ├── main/                            ← [主代码]
    │   ├── java/                        ← [Java 源文件] 约定：必须放这里
    │   │   └── com/                     ← [第一层包] 组织名倒写
    │   │       └── example/             ← [第二层包] 组织名
    │   │           └── ccagent/         ← [第三层包] 项目名
    │   │               │
    │   │               ├── CcAgentApplication.java    ← [入口] main() 在这里
    │   │               │
    │   │               ├── config/                    ← [配置层]
    │   │               │   └── AgentProperties.java   ← 绑定 application.yml
    │   │               │
    │   │               ├── model/                     ← [数据层]
    │   │               │   ├── Message.java           ← 一条对话消息
    │   │               │   ├── ToolCall.java          ← Claude 要调工具
    │   │               │   ├── ToolResult.java        ← 工具执行结果
    │   │               │   └── ChatRequest.java       ← 用户 HTTP 请求体
    │   │               │
    │   │               ├── controller/                ← [接口层]
    │   │               │   └── ChatController.java    ← HTTP 路由
    │   │               │
    │   │               ├── service/                   ← [业务层]
    │   │               │   ├── AnthropicClient.java   ← 调 Claude API
    │   │               │   ├── ToolRegistry.java      ← 管理工具
    │   │               │   └── AgentService.java      ← Agent 核心循环
    │   │               │
    │   │               └── tool/                      ← [工具层]
    │   │                   ├── AgentTool.java         ← 工具接口 (interface)
    │   │                   ├── FileReadTool.java      ← 读文件
    │   │                   ├── FileWriteTool.java     ← 写文件
    │   │                   └── BashTool.java          ← 执行命令
    │   │
    │   └── resources/                    ← [配置文件根目录]
    │       └── application.yml           ← Spring Boot 配置文件
    │
    └── test/                             ← [测试代码] 目前为空

在右边添加注释框解释每层的作用：

┌─────────────────────┐
│ 目录 = 包名的映射规则  │
│                     │
│ 文件路径：           │
│  src/main/java/     │
│   com/example/      │
│   ccagent/tool/     │
│   FileReadTool.java │
│                     │
│ 这个文件的开头必须写： │
│ package com.example  │
│   .ccagent.tool;    │
│                     │
│ 目录路径 = 包名     │
│ 不一致→编译器报错   │
└─────────────────────┘
```

## Prompt 2：Java 文件内部结构（解剖一个 .java 文件）

```
画一个图，解剖一个 Java 文件的内部结构。

用 FileReadTool.java 为例，逐行标注每个部分的名称和作用。

┌─ FileReadTool.java 文件内部结构 ──────────────────────────────┐
│
│  // 第 1 行：包声明（必须和文件所在目录一致）
│  package com.example.ccagent.tool;
│
│  // 第 2-10 行：导入语句（引入其他包里的类）
│  import java.nio.file.Files;       ← Java 标准库
│  import java.nio.file.Path;        ← Java 标准库
│  import java.util.Map;             ← Java 标准库
│  import org.springframework...;    ← Spring 框架
│
│  // 第 12-N 行：类定义
│  @Component                 ← 注解：告诉 Spring 自动创建这个类的实例
│  public class FileReadTool   ← 类名
│      implements AgentTool {  ← 实现 AgentTool 接口
│
│      // 方法 1
│      @Override               ← 注解：标记这是实现接口的方法
│      public String getName() {
│          return "file_read";
│      }
│
│      // 方法 2
│      @Override
│      public String getDescription() {
│          return "读取文件内容";
│      }
│
│      // 方法 3
│      @Override
│      public String execute(Map<String, Object> input)
│          throws Exception {
│          String path = (String) input.get("path");
│          return Files.readString(Path.of(path));
│      }
│  }
│
└──────────────────────────────────────────────────────────────┘

旁边标注：
- 一个 .java 文件里只能有一个 public 类（通常）
- 文件名必须和 public 类名一致
- import 只引入需要的类，不引入整个包
```

## Prompt 3：包名、目录、import 的关系

```
画一个图，解释 Java 中"包名 = 目录"这个核心规则。

分左右两栏：

左栏：文件系统（目录结构）              右栏：Java 代码

src/main/java/                         
  └── com/
      └── example/
          └── ccagent/
              ├── controller/
              │   └── ChatController.java  →  package com.example.ccagent.controller;
              │                                import com.example.ccagent.service.AgentService;
              │                                ↑
              │                  这个 import 需要知道 AgentService 在哪个包
              │
              └── service/
                  └── AgentService.java     →  package com.example.ccagent.service;
                      ↑
                      │
          import 的本质：                         
          编译器去 src/main/java/com/example/ccagent/service/                      
          目录下找 AgentService.class                                           

用箭头标出 import 语句如何对应到文件系统路径：
  import com.example.ccagent.service.AgentService
   │      │       │       │       │
   │      │       │       │       └── 类名（文件名叫 AgentService.java）
   │      │       │       └── 子包（目录名叫 service/）
   │      │       └── 项目名（目录名叫 ccagent/）
   │      └── 组织名（目录名叫 example/）
   └── 顶级域名（目录名叫 com/）

额外标注：
- java.util.Map → 去 JDK 自带的标准库找，不是项目里的
- org.springframework... → 去 Gradle 下载的 JAR 包里找
- com.example.ccagent... → 去项目自己的 src/main/java/ 里找
```

## Prompt 4：Spring Boot 如何把组件连接起来

```
画一个连环画风格的图，解释 Spring Boot 启动时如何扫描、创建、连接组件。

分 6 个步骤，每步一张小图：

步骤 1：Spring 扫描
┌─────────────────────────────┐
│ Spring Boot 启动            │
│ 扫描 com.example.ccagent 包 │
│ 找到所有带 @Component 的类  │
└─────────────────────────────┘

步骤 2：创建实例
┌─────────────────────────────┐
│ 为每个 @Component 类        │
│ 调用 new 创建实例           │
│                             │
│ new FileReadTool()  ──→ [实例1] │
│ new FileWriteTool() ──→ [实例2] │
│ new BashTool()      ──→ [实例3] │
│ new AnthropicClient()──→ [实例4] │
│ new ToolRegistry()  ──→ [实例5] │
│ new AgentService()  ──→ [实例6] │
│ new ChatController()──→ [实例7] │
└─────────────────────────────┘

步骤 3：放入容器
┌─────────────────────────────┐
│ Spring 容器（一个大 Map）    │
│                             │
│ "fileReadTool"  → [实例1]  │
│ "fileWriteTool" → [实例2]  │
│ "bashTool"      → [实例3]  │
│ "anthropicClient"→[实例4]  │
│ "toolRegistry"  → [实例5]  │
│ "agentService"  → [实例6]  │
│ "chatController"→ [实例7]  │
└─────────────────────────────┘

步骤 4：检查 @Autowired
┌─────────────────────────────┐
│ 检查每个实例的字段          │
│                             │
│ ToolRegistry 的构造函数:    │
│   @Autowired                │
│   public ToolRegistry(      │
│     List<AgentTool> list)   │
│                             │
│ Spring 在容器里找到所有     │
│ AgentTool 实现:             │
│   [实例1] [实例2] [实例3]   │
│ 放进 List 传给构造函数      │
└─────────────────────────────┘

步骤 5：完成连接
┌─────────────────────────────┐
│ 所有 @Autowired 字段        │
│ 都指向了正确的实例          │
│                             │
│ ChatController              │
│   └→ AgentService           │
│       ├→ AnthropicClient    │
│       │   └→ AgentProperties│
│       └→ ToolRegistry       │
│           └→ [实例1,2,3]    │
└─────────────────────────────┘

步骤 6：启动 Tomcat
┌─────────────────────────────┐
│ Tomcat 启动，监听 8080 端口 │
│ 注册 HTTP 路由              │
│ POST /api/chat → chat()     │
│ GET  /api/chat/stream → chatStream()│
└─────────────────────────────┘
```

## Prompt 5：关键语法速查图

```
画一个表格形式的语法速查图，C++ 程序员一眼能看懂 Java。

分四类语法：

┌─────────────────────────────────────────────────────────────┐
│ 类和接口                                                     │
├──────────────┬──────────────────────┬────────────────────────┤
│ Java         │ 含义                 │ C++                    │
├──────────────┼──────────────────────┼────────────────────────┤
│ class A {}   │ 定义一个类           │ class A {};            │
│ interface I{}│ 定义纯虚接口         │ class I {virtual...=0;};│
│ implements I │ 实现接口             │ : public I             │
│ extends B    │ 继承类               │ : public B             │
│ public       │ 公开（外部可访问）     │ public:                │
│ private      │ 私有（仅类内部）       │ private:               │
│ @Override    │ 标记重写（写错报错）   │ override               │
└──────────────┴──────────────────────┴────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 注解（C++ 里没有直接对应，是"给代码打标签"的机制）             │
├──────────────────────┬──────────────────────────────────────┤
│ Java                 │ 含义                                 │
├──────────────────────┼──────────────────────────────────────┤
│ @Component           │ 告诉 Spring：自动创建这个类的实例       │
│ @Service             │ 同 @Component，语义：业务逻辑类         │
│ @RestController      │ 同 @Component，语义：HTTP 控制器        │
│ @Autowired           │ 从容器取出实例，连接到这个字段（传引用）  │
│ @PostMapping("/x")   │ 当 POST /x 请求到达时，调用这个方法     │
│ @GetMapping("/x")    │ 当 GET /x 请求到达时，调用这个方法      │
│ @RequestBody         │ 把 HTTP 请求体 JSON 转成 Java 对象     │
│ @RequestParam        │ 把 URL 参数转成 Java 变量             │
│ @Async               │ 这个方法在独立线程执行                 │
│ @EnableAsync         │ 启用异步支持（放在入口类上）            │
│ @ConfigurationProperties│ 把 yml 配置映射到 Java 对象        │
└──────────────────────┴──────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 数据结构和集合                                               │
├──────────────┬──────────────────────┬────────────────────────┤
│ Java         │ 含义                 │ C++                    │
├──────────────┼──────────────────────┼────────────────────────┤
│ String       │ 字符串               │ std::string            │
│ int          │ 整数                 │ int                    │
│ boolean      │ 布尔值               │ bool                   │
│ List<T>      │ 有序列表             │ std::vector<T>         │
│ Map<K,V>     │ 键值对               │ std::map<K,V>          │
│ record R(...)│ 不可变数据类          │ struct + 自动生成方法  │
│ new 类名()   │ 创建对象             │ new 类名() 或 栈对象   │
└──────────────┴──────────────────────┴────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ 线程和异步                                                   │
├──────────────┬──────────────────────────────────────────────┤
│ Java         │ 含义                                         │
├──────────────┼──────────────────────────────────────────────┤
│ @Async       │ 方法在 Spring 管理的线程池里异步执行          │
│ Completable- │ 异步任务的返回值，"结果还没好，好了给你"       │
│ Future<T>    │                                              │
│ .thenApply() │ 等结果好了之后做什么                          │
│ @EnableAsync │ 打开异步开关（不加这行 @Async 不生效）        │
└──────────────┴──────────────────────────────────────────────┘
```

## 使用方式

1. 复制对应的 Prompt
2. 粘贴到支持绘图和 mermaid 的 AI 工具
3. Prompt 1-4 适合在 Claude/ChatGPT 中生成图文
4. Prompt 5 是表格，可以直接用
