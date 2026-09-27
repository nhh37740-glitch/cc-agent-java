# CC Agent (Java)

Java 17 与 Spring Boot 实现的 DeepSeek Agent 学习项目。页面入口为
`/agent.html`；服务提供聊天、SSE 流式回答及会话读取接口。

## 模块

- `agent-contracts/`：消息、会话数据类型及 `AgentTool` 接口。只依赖 Java 17
  和 Jackson 注解；没有 Spring、数据库或 HTTP 实现依赖。
- 根项目 `src/`：Spring Boot HTTP 接口、Agent 循环、DeepSeek 客户端、
  文件与命令工具、会话存储和 SQLite 备份。它依赖 `agent-contracts`。

`scripts/check_boundaries.py` 检查契约模块的依赖方向。Gradle 编译进一步
保证该模块无法直接引用根应用类，因为它没有反向项目依赖。

## 本地构建与发布

Windows 上的 `run.bat` 使用项目目录下的 JDK 17 启动服务。
`DEEPSEEK_API_KEY` 必须由环境变量提供。

```powershell
./gradlew.bat build
python scripts/release.py
java -jar dist/cc-agent-java-1.0.0.jar
```

发布脚本先检查模块边界并执行 Gradle build，然后生成可运行 JAR 和
`dist/manifest.json`。清单包含 Git commit、commit tree、工作树 SHA-256、
Java/Gradle 版本以及 JAR 的 SHA-256。

## Docker 与 Jenkins

`Dockerfile` 在 JDK 17 容器中构建并验证 JAR，运行镜像保留 JDK、Git 和
ripgrep，让 workspace 内的 Gradle Wrapper 与白名单命令可执行。生产数据
保存在 `/app/workspace`，日志在 `/app/logs`。

```bash
docker build -t cc-agent-java:local .
docker run --rm -p 127.0.0.1:8082:8080 \
  -e DEEPSEEK_API_KEY \
  -v /srv/cc-agent-java/workspace:/app/workspace \
  -v /srv/cc-agent-java/logs:/app/logs \
  cc-agent-java:local
```

Jenkinsfile 在带 Docker 的 Linux 节点上执行模块检查、Gradle 构建、
JAR 与清单归档以及运行镜像构建。容器以非 root 用户运行；服务器上的
挂载目录需要允许 UID 10001 写入。对外提供入口时应由已认证的反向代理
接入，并只挂载 Agent 需要处理的工作目录。
