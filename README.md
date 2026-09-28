# CC Agent (Java)

Java 17 与 Spring Boot 实现的 DeepSeek Agent 学习项目。页面入口为
`/agent.html`；服务提供聊天、SSE 流式回答及会话读取接口。

## 模块

- `agent-contracts/` 是共享 Java 类型的唯一源码所有者，包括消息与会话模型、`Compressor` 和 `AgentTool`。它只依赖 Java 17 与 Jackson 注解，不依赖 Spring、数据库或 HTTP 实现。
- 根项目 `src/` 提供 Spring Boot HTTP 接口、Agent 循环、DeepSeek 客户端、具体工具与会话存储，并通过 Gradle 项目依赖使用 `agent-contracts`。
- `scripts/check_boundaries.py` 检查模块依赖方向，并确保上述 9 个共享类型只在 `agent-contracts` 中声明；根应用出现重复全名会使门禁失败。

## 服务端构建与二进制交付

Codex 的构建与运行验证在 Linux Jenkins 节点的 Docker 环境中完成，不在本机执行 Gradle 或 `javac`。Jenkins 流水线先运行模块边界检查与 Gradle 构建，归档可运行 Spring Boot JAR 和 `dist/manifest.json`，再构建运行镜像。manifest 记录源码 commit、工作树摘要、工具链版本及 JAR SHA-256。

交付物是一个包含 `agent-contracts` 依赖的可运行应用 JAR，以及该 JAR 的校验清单；目前不单独发布 `agent-contracts` 二进制包。`DEEPSEEK_API_KEY` 可作为服务器默认密钥由部署环境注入，不写入源码或镜像。

网页可在 `/agent.html` 打开“DeepSeek API Key”设置页。输入页
`/deepseek-key.html` 只加载本站 CSS/JS，并仅在 HTTPS 或本机回环地址
开放密钥输入。密钥保存在当前浏览器的服务端会话内存中，不写入仓库、
数据库、浏览器存储或 URL。`GET /api/settings/deepseek` 只返回是否配置、
来源和掩码；`PUT` / `DELETE` 只更改本会话密钥。聊天、流式请求与记忆
压缩使用同一会话的密钥；未设置或移除网页密钥后使用环境变量默认值。
服务重启或会话到期后网页密钥失效；多实例部署需要会话亲和或共享的
安全会话存储。公开入口需要 HTTPS 才能从网页输入密钥。
服务端 `PUT /api/settings/deepseek` 还会核对 `Origin` 与 `Host`：只接受
同源 HTTPS，或同源的 localhost/回环 HTTP。TLS 在反向代理终止时，需将
容器看到的代理 IP 加入 `DEEPSEEK_TRUSTED_PROXY_ADDRESSES`（逗号分隔；
默认只信任回环地址），并由代理覆盖 `X-Forwarded-Proto`。单独伪造该
请求头不能绕过检查；移除密钥的 `DELETE` 行为不变。

## Docker 与 Jenkins

`Dockerfile` 在 JDK 17 构建镜像中运行边界检查与打包脚本，再生成以 UID 10001 非 root 用户运行的镜像。运行容器保留 JDK、Git 和 ripgrep，使 workspace 内的白名单工具可执行；生产数据位于 `/app/workspace`，日志位于 `/app/logs`。公共入口应经过已认证的反向代理，并只挂载 Agent 需要访问的工作目录。

`Jenkinsfile` 在带 Docker 的 Linux 节点执行构建、归档和运行镜像生成。Codex 按项目约定只做静态检查并通过 Jenkins 完成编译、集成验证和部署；不要在本机运行 Gradle 或 `javac`。
Gradle `build` 会运行 DeepSeek 会话隔离、接口响应掩码和请求头选择测试；
启用 `DeployDemo` 时还会检查密钥设置页、脚本、状态接口和公开 HTTP 页面
默认隐藏并禁用密钥输入框。

### 演示机部署

Jenkins 参数 `DeployDemo` 是布尔开关，默认关闭。只有显式勾选后，流水线才会在镜像构建成功后
调用 `scripts/deploy-demo.sh`，把现有回环端口 `127.0.0.1:18102` 切换到新镜像。
部署脚本从当前容器继承环境变量、已有数据卷和内存/CPU 限额，不把密钥写入日志；旧镜像保留为
`cc-agent-java:previous`。新容器必须通过 Docker 健康检查、`/agent.html` 页面检查和
`/api/conversations` API 检查，否则脚本会移除新容器并恢复原容器。
容器的 `/tmp` 使用 64 MiB tmpfs，显式允许执行 SQLite JDBC 解压出的 native 库，并保持
`nosuid,nodev` 与标准临时目录权限；SQLite native 提取需要 `exec` 挂载选项。

首次迁移时，如果旧容器没有挂载 `/app/workspace`，脚本会停止旧容器以固定 SQLite 数据，创建
命名卷 `cc-agent-java-workspace`，并通过 Docker tar 流把容器层的 workspace 直接复制进卷；数据
不会经过宿主机临时目录。复制成功后，卷内会写入包含源容器 ID 的迁移标记。再次执行时，只有
标记匹配当前旧容器才会复用该卷；已存在但无标记或标记不匹配的卷会被拒绝，不会清空或覆盖。
失败时旧容器层保持原样并恢复启动；本次新建的卷会在回滚后清理。若旧容器已有
`/app/workspace` 挂载，则继续复用原挂载。

该端口固定，替换期间会有短暂不可用窗口；健康检查失败时会自动回滚。部署参数关闭时流水线
只验证、归档并构建镜像，不改动正在运行的演示服务。
