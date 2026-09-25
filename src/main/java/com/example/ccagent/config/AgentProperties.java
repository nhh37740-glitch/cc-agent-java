package com.example.ccagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置绑定类
 *
 * 作用：把 application.yml 里的基础运行配置自动映射到 Java 对象的字段。
 * 系统提示词和工具调用轮数由 agent.xml 管理。
 *
 * application.yml 写：
 *   agent:
 *     api-key: sk-xxx
 *     model: claude-sonnet-4-20250514
 *
 * 这个类自动绑定：
 *   props.apiKey()  → "sk-xxx"
 *   props.model()   → "claude-sonnet-4-20250514"
 */
// @ConfigurationProperties(prefix = "agent")
//   prefix = "agent" 表示：去 application.yml 里找 agent: 下面的字段
//   Spring 自动把 yml 的横杠命名（api-key）转成 Java 的驼峰命名（apiKey）
//   例如：api-endpoint → apiEndpoint, api-key → apiKey
//
// 注意：@ConfigurationProperties 不需要 @Configuration 配合也能工作
// Record 是隐式 final 的，不能用 @Configuration（Spring 要求配置类不能是 final）
//
// Java Record（Java 16+ 语法）：
//   定义一个不可变数据类，编译器自动生成：
//     1. 构造函数（所有字段作为参数）
//     2. getter 方法（直接用字段名，没有 get 前缀，如 apiEndpoint()）
//     3. equals() — 比较两个对象是否相等
//     4. hashCode() — 哈希值（用于 HashMap 等集合）
//     5. toString() — 打印时的文本表示
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
    // Claude API 的地址
    String apiEndpoint,

    // API 密钥（从环境变量读取，不硬编码）
    String apiKey,

    // 使用的模型名称
    String model,

    // 当前会话的上下文窗口上限，用来观察当前输入 token 离窗口上限还有多远
    int contextWindowLimitTokens,

    // workspaceDir 安全地指定工作目录，Anthropic 只能访问这个目录下的文件
    // PathValidator 用它来校验路径，防止工具越权访问系统目录
    String workspaceDir,

    // 压缩触发阈值。runningTotalTokens 超过此值时触发记忆压缩，默认 100000
    int compressionThresholdTokens,

    // 工具结果最大字符数。超过此值的工具输出会被截断，防止大文件撑爆上下文
    int maxToolResultChars
) {}
