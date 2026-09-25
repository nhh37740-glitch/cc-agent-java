# 注释规范

## 核心原则

**解释代码在做什么，不说专业术语，不类比其他语言。**

## 系统数据流

理解数据流是写好注释的前提。完整流程：

```
用户用 curl/Postman/前端界面 发送 POST 请求
    ↓
Tomcat（8080 端口）接收请求
    ↓
Spring 根据注解匹配路由
    ↓
调用 Controller 方法
    ↓
Controller 调用 Service
    ↓
Service 执行业务逻辑
    ↓
返回响应给用户
```

## 语法模式

### 1. package

package = 声明这个文件属于哪个包。包名必须和文件所在目录完全一致。

```java
// package com.example.ccagent.tool;
//   │    │       │       │
//   │    │       │       └── 项目名
//   │    │       └── 组织名
//   │    └── 顶级域名
//   └── 固定写法
//
// 规则：包名 = 目录路径
//   文件路径: src/main/java/com/example/ccagent/tool/AgentTool.java
//   包声明:   package com.example.ccagent.tool;
//   如果不匹配，编译器报错
package com.example.ccagent.tool;
```

### 2. import

import = 引入其他包的类，没有这行编译器找不到这些类。

```java
// import org.springframework.web.bind.annotation.PostMapping;
//   │    │       │         │          │
//   │    │       │          │          └── 类名
//   │    │       │          └── 子包
//   │    │       └── 项目名
//   │    └── 组织名
//   └── 固定写法
//
// 三类 import：
//   1. Spring 框架：org.springframework.xxx（自动配置、注解、Web 支持）
//   2. 项目内部：com.example.ccagent.xxx（我们自己写的类）
//   3. Java 标准库：java.xxx（Java 自带的类，如 Map、List）
import org.springframework.web.bind.annotation.PostMapping;

// Spring 相关包的作用：
//   org.springframework.boot.SpringApplication — 启动 Spring Boot 应用
//   org.springframework.boot.autoconfigure.SpringBootApplication — 自动配置注解
//   org.springframework.boot.context.properties.ConfigurationProperties — 配置绑定
//   org.springframework.context.annotation.Configuration — 配置类标记
//   org.springframework.beans.factory.annotation.Autowired — 自动创建实例并注入
//   org.springframework.stereotype.Component — 标记为 Spring 管理的组件
//   org.springframework.stereotype.Service — 标记为业务逻辑类
//   org.springframework.web.bind.annotation.RestController — 标记为 HTTP 控制器
//   org.springframework.web.bind.annotation.PostMapping — 处理 POST 请求
//   org.springframework.web.bind.annotation.RequestBody — 从请求体取值
//   org.springframework.web.bind.annotation.RequestMapping — 设置路由前缀

// Java 标准库包的作用：
//   java.util.Map — 键值对集合（类似 JSON 对象）
//   java.util.List — 有序列表
//   java.nio.file.Files — 文件读写工具
//   java.nio.file.Path — 文件路径

// 项目内部包的作用：
//   com.example.ccagent.model.Message — 对话消息数据类
//   com.example.ccagent.model.ToolCall — 工具调用数据类
//   com.example.ccagent.model.ToolResult — 工具结果数据类
//   com.example.ccagent.model.ChatRequest — 前端请求体
//   com.example.ccagent.tool.AgentTool — 工具接口
//   com.example.ccagent.tool.FileReadTool — 文件读取工具
//   com.example.ccagent.service.AgentService — 核心 Agent 循环
//   com.example.ccagent.service.ClaudeClient — Claude API 调用
//   com.example.ccagent.controller.ChatController — HTTP 控制器
//   com.example.ccagent.config.AgentProperties — 配置绑定
```

### 3. 注解

注解 = 告诉 Spring 做某件事的标记。

```java
// @XxxAnnotation = 这个注解做了什么（用一句话说清楚）
```

常用注解：

```java
// @RestController = 标记这个类是控制器，Spring 会扫描它并注册 HTTP 路由
@RestController

// @RequestMapping("/api") = 这个控制器所有接口都以 /api 开头
@RequestMapping("/api")

// @PostMapping("/chat") = 当用户 POST 请求发到 /api/chat 时，调用这个方法
// 注意：这里的 POST 是用户主动发过来的，不是程序主动发出去的
@PostMapping("/chat")

// @Autowired = 告诉 Spring："帮我创建这个类的实例并放到这个字段里"
@Autowired

// @Component = 告诉 Spring："自动创建这个类的实例并管理"
@Component

// @Service = 标记为业务逻辑类
@Service

// @Configuration = 告诉 Spring："这是配置类，里面有需要管理的配置"
@Configuration

// @ConfigurationProperties(prefix = "agent") = 把 application.yml 里 agent: 下面的字段自动映射到这个类的字段
@ConfigurationProperties(prefix = "agent")

// @EnableAsync = 启用异步支持，让 @Async 注解生效
@EnableAsync

// @Async = 标记这个方法在单独线程执行（异步）
// 返回 CompletableFuture<String> 表示异步返回结果
@Async
public CompletableFuture<String> run(String userMessage) { ... }
```

### 2. 方法签名

逐个拆解，每个部分一行注释：

```java
// @PostMapping("/chat") = 注册路由：当用户 POST 请求发到 /api/chat 时，调用这个方法
// 完整流程：用户 POST 请求 → Tomcat（8080 端口）→ Spring → 匹配这个注解 → 调用 chat() 方法
// public Map<String, String> = 返回类型是 Map，键和值都是 String
// chat = 方法名（随便起）
// @RequestBody ChatRequest request = 把用户发来的 JSON 请求体自动转成 ChatRequest 对象
//   - @RequestBody = 标记：从请求体取值
//   - ChatRequest = 类型：转成这个对象（定义在 ChatRequest.java 里）
//   - request = 变量名（随便起）
// { ... } = 方法体
@PostMapping("/chat")
public Map<String, String> chat(@RequestBody ChatRequest request) {
```

### 3. Record 类

Record = 不可变数据类，编译器自动生成构造函数、getter、equals()、hashCode()、toString()。

```java
// Record = 不可变数据类
//   - String message = 构造参数（也是字段）
//   - 编译器自动生成 message() getter 方法
//   - 创建后不能修改字段值
public record ChatRequest(String message) {}
```

### 4. @RequestBody JSON 转换

`spring-boot-starter-web` 自带 Jackson 库，自动做 JSON ↔ Java 对象转换。

转换原理：Jackson 根据 JSON 的键名匹配 Record 的构造参数名。

```java
// JSON: {"message": "帮我看 main.java"}
//       │         │
//       │         └── 匹配构造参数 message
//       └── JSON 键名
public record ChatRequest(String message) {}
//                              │
//                              └── 构造参数名必须和 JSON 键名一致
```

JSON 键名必须和 Record 构造参数名一致，否则转换失败。开发时需要对照 API 文档确认 JSON 字段名。

### 5. 工具接口

接口 = 定义契约，所有工具必须实现这三个方法。

```java
// interface = 接口，定义所有工具必须实现的方法
// AgentTool = 接口名
public interface AgentTool {
    // 工具名称，Claude 用这个名字调用工具
    String getName();

    // 工具描述，告诉 Claude 这个工具能做什么
    String getDescription();

    // 执行工具逻辑，input 是参数键值对，返回执行结果
    String execute(Map<String, Object> input) throws Exception;
}
```

### 6. 类定义

```java
// public class = 定义一个公开的类
// implements AgentTool = 实现 AgentTool 接口（必须实现 getName、getDescription、execute 三个方法）
// @Component = 告诉 Spring："自动创建这个类的实例并管理"
@Component
public class FileReadTool implements AgentTool {
```

### 7. 构造函数 + 依赖注入

```java
// private final Map<String, AgentTool> tools;
//   - private = 只能在本类内部访问
//   - final = 创建后不能修改（只能赋值一次）
//   - Map<String, AgentTool> = 键值对集合，key 是 String，value 是 AgentTool
//   - tools = 变量名
private final Map<String, AgentTool> tools;

// @Autowired = 告诉 Spring："帮我找到所有实现了 AgentTool 接口的类，创建实例，注入到这个参数里"
// public ToolRegistry(List<AgentTool> toolList) = 构造函数
//   - List<AgentTool> = 有序列表，存放所有工具实例
//   - toolList = 参数名
// Spring 自动调用这个构造函数，把所有工具实例注入到 toolList
@Autowired
public ToolRegistry(List<AgentTool> toolList) {
    // toolList.stream() = 把 List 转成流（可以链式处理）
    // .collect(Collectors.toMap(...)) = 把流转成 Map
    // AgentTool::getName = 用工具的 getName() 方法作为 Map 的 key
    // t -> t = 值就是工具实例本身
    // 结果：{"file_read" → FileReadTool, "file_write" → FileWriteTool, ...}
    this.tools = toolList.stream()
        .collect(Collectors.toMap(AgentTool::getName, t -> t));
}
```

### 8. try-catch 异常处理

```java
// try { ... } = 尝试执行代码
// catch (Exception e) = 如果发生异常，执行这里的代码
// e.getMessage() = 获取异常的错误信息
try {
    String result = tool.execute(input);
    return new ToolResult(name, result, false);
} catch (Exception e) {
    return new ToolResult(name, e.getMessage(), true);
}
```

### 9. 强制类型转换

Map 里存的是 Object 类型，取出时需要强制转换成具体类型：

```java
// (String) input.get("path") = 从 Map 中取出 "path" 的值，强制转成 String
// (String) 是强制类型转换语法
// input.get("path") 返回的是 Object 类型，需要转成 String 才能使用
String path = (String) input.get("path");
String content = (String) input.get("content");
String command = (String) input.get("command");
```

### 10. 执行 shell 命令

```java
// Runtime.getRuntime().exec() = 执行 shell 命令
// new String[]{"/bin/sh", "-c", command} = 命令参数数组
//   - /bin/sh = 使用 sh shell 执行
//   - -c = 表示后面跟着的是命令字符串
//   - command = 要执行的命令
Process process = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});

// process.getInputStream() = 获取命令的标准输出流
// readAllBytes() = 读取全部输出
// new String(...) = 把字节数组转成字符串
String output = new String(process.getInputStream().readAllBytes());

// process.waitFor() = 等待命令执行完成
// process.exitValue() = 获取退出码（0=成功，非0=失败）
int exitCode = process.waitFor();

// process.getErrorStream() = 获取错误输出流
String error = new String(process.getErrorStream().readAllBytes());
```
    return new ToolResult(name, e.getMessage(), true);
}
```

## 关键概念

| 概念 | 说明 |
|------|------|
| package | 声明文件属于哪个包，必须和目录一致 |
| import | 引入其他包的类，没有这行编译器找不到 |
| 注解 | 给代码打的标记，Spring 读取它来决定做什么 |
| @Component | 标记这个类需要 Spring 自动创建实例 |
| @Service | 同 @Component，语义上表示"业务逻辑类" |
| @Controller | 同 @Component，语义上表示"处理 HTTP 请求" |
| @Autowired | 把 Spring 容器里的实例连接到这个字段（传引用，不是拷贝） |
| @EnableAsync | 启用异步支持，让 @Async 注解生效 |
| @Async | 标记方法在单独线程执行（异步），不阻塞当前线程 |
| CompletableFuture | 异步任务的返回类型，表示"结果还没好，之后给你" |
| @RequestBody | 把用户发来的 JSON 请求体自动转成 Java 对象 |
| Record | 不可变数据类，编译器自动生成构造函数和 getter |
| interface | 接口，定义所有工具必须实现的方法 |
| implements | 实现接口（必须实现接口定义的所有方法） |
| List | 有序列表，按顺序存放多个元素 |
| Map | 键值对集合，按 key 快速查找 value |
| stream() | 把集合转成流，可以链式处理（过滤、转换等） |
| Collectors.toMap | 把流转成 Map（key→value 键值对） |
| try-catch | 异常处理：try 里的代码如果报错，跳到 catch 执行 |
| final | 字段创建后不能修改（只能赋值一次） |
| static | 静态方法，不需要创建对象就能调用 |
| POST | 用户主动发过来的请求，不是程序主动发出去的 |
| Tomcat | Web 服务器，接收 HTTP 请求并转发给 Spring |
| Jackson | JSON 和 Java 对象之间的自动转换库 |
| 单例 | Spring 默认每个类只创建一个实例，所有 @Autowired 拿到同一个对象 |
