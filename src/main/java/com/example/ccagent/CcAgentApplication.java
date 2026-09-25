// package = 声明这个文件属于哪个包
// com.example.ccagent = 包名，必须和文件所在目录一致
package com.example.ccagent;

import java.nio.file.Files;
import java.nio.file.Path;

// import = 引入其他包的类，没有这行编译器找不到这些类
// SpringApplication = 启动 Spring Boot 应用的工具类
import org.springframework.boot.SpringApplication;
// @SpringBootApplication = 标记这是 Spring Boot 应用入口，自动完成配置和组件扫描
import org.springframework.boot.autoconfigure.SpringBootApplication;
// @EnableConfigurationProperties = 告诉 Spring 把 @ConfigurationProperties 类注册为 Bean
// AgentProperties 是 Record，不能用 @Configuration，所以在这里启用
import org.springframework.boot.context.properties.EnableConfigurationProperties;
// @EnableAsync = 启用异步支持，让 @Async 注解生效
import org.springframework.scheduling.annotation.EnableAsync;

// import = 引入项目内部的类
import com.example.ccagent.config.AgentProperties;

/**
 * Spring Boot 应用入口
 *
 * @SpringBootApplication 做了三件事：
 *   1. 标记这是配置类
 *   2. 启用自动配置（Spring Boot 自动帮你配置 Web 服务器、JSON 解析等）
 *   3. 扫描当前包及子包下所有带 @Component 的类，自动创建实例
 */
// @SpringBootApplication = 标记这是 Spring Boot 应用入口
//   - 自动配置：Web 服务器、JSON 解析等
//   - 组件扫描：找到所有带 @Component 的类，自动创建实例
// @EnableAsync = 启用异步支持，让 @Async 注解生效
//   - 加了这个注解后，@Async 标记的方法会在单独线程执行
//   - 不加这个注解，@Async 不会生效
// @EnableConfigurationProperties = 告诉 Spring 把 AgentProperties 注册为 Bean
//   因为 AgentProperties 是 Record（隐式 final），不能用 @Configuration 标记
//   所以通过这个注解在入口类上注册它
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties(AgentProperties.class)
public class CcAgentApplication {

    /**
     * 程序入口，Java 程序从这里开始执行
     *
     * SpringApplication.run() 做了三件事：
     *   1. 创建所有 @Component 标记的类的实例
     *   2. 启动内嵌的 Tomcat Web 服务器（默认监听 8080 端口）
     *   3. 开始监听 HTTP 请求
     */
    // public = 公开方法
    // static = 静态方法，不需要创建对象就能调用
    // void = 没有返回值
    // main = 方法名，Java 程序的入口点（和 C++ 的 int main() 一样）
    // String[] args = 命令行参数数组
    public static void main(String[] args) {
        try {
            Files.createDirectories(Path.of("workspace", "data", "sessions"));
        } catch (Exception e) {
            throw new IllegalStateException("创建 workspace/data/sessions 目录失败", e);
        }
        // SpringApplication.run() = 启动 Spring Boot 应用
        // CcAgentApplication.class = 当前类（告诉 Spring 从这个类开始扫描）
        // args = 命令行参数
        SpringApplication.run(CcAgentApplication.class, args);
    }
}
