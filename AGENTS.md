# 项目说明

## 项目定位

本项目是一个 **AI 零代码应用生成平台**。用户通过自然语言描述需求，后端调用 AI 生成可运行的网站或应用代码，前端实时展示生成过程和预览效果，并支持应用管理、源码下载和一键部署。

项目当前同时保留了两套后端形态：

- 根目录 `src/`：单体 Spring Boot 应用。
- `zzx-ai-code-microservice/`：微服务拆分版本，按公共能力、模型、用户、应用、AI、截图等模块拆分。

前端位于 `zzx-ai-code-frontend/`，是 Vue 3 + TypeScript + Vite 项目。

## 主要目录

```text
.
├── src/                              # 单体后端源码
├── zzx-ai-code-microservice/          # 微服务后端聚合工程
│   ├── zzx-ai-code-common/            # 公共配置、异常、常量、工具、COS 等
│   ├── zzx-ai-code-model/             # PO、DTO、VO、枚举等数据模型
│   ├── zzx-ai-code-client/            # 内部服务接口
│   ├── zzx-ai-code-user/              # 用户服务
│   ├── zzx-ai-code-app/               # 应用服务、代码生成入口、部署与静态资源
│   ├── zzx-ai-code-ai/                # AI 服务、模型配置、工具调用、提示词
│   └── zzx-ai-code-screenshot/        # 网页截图服务
├── zzx-ai-code-frontend/              # 前端项目
├── sql/                               # 数据库建表脚本
├── imgs/                              # README 和文档图片
├── grafana/                           # Grafana 仪表盘配置
├── prometheus.yml                     # Prometheus 抓取配置
├── pom.xml                            # 单体后端 Maven 配置
└── README.md                          # 项目说明
```

## 技术栈

### 后端

- Java 21
- Spring Boot 3.x
- Spring Cloud Alibaba
- Dubbo + Nacos，微服务间使用 Triple 协议
- MyBatis-Flex + MySQL
- Redis + Spring Session
- Redisson，用于限流等分布式能力
- Caffeine，本地缓存
- LangChain4j、LangGraph4j、Tool Calling
- OpenAI 兼容接口、DeepSeek、DashScope
- Selenium + WebDriverManager，用于网页截图
- 腾讯 COS，用于对象存储
- Knife4j / Swagger，用于接口文档
- Spring Boot Actuator + Micrometer Prometheus，用于监控

### 前端

- Vue 3
- TypeScript
- Vite
- Ant Design Vue
- Vue Router
- Pinia
- Axios
- OpenAPI 生成接口代码
- Markdown 渲染与代码高亮：`markdown-it`、`highlight.js`

## 后端模块与职责

### 单体应用

入口类：

- `src/main/java/com/zzx/zzxaicode/ZzxAiCodeApplication.java`

默认配置：

- 服务端口：`8123`
- 接口前缀：`/api`
- 数据库：`zzx_ai_code`
- Session：Redis 存储，Cookie 默认 30 天
- Actuator Prometheus 指标：`/api/actuator/prometheus`

主要包职责：

- `controller`：用户、应用、聊天历史、群聊、静态资源、健康检查、工作流 SSE 等接口。
- `service` / `service.impl`：业务逻辑。
- `ai`：AI 代码生成服务、模型路由、工具调用、护栏。
- `core`：代码解析、保存、Vue 项目构建、流式处理。
- `langgraph4j`：代码生成工作流，包括图片收集、提示词增强、路由、代码生成、项目构建、质量检查。
- `model`：PO、DTO、VO、枚举。
- `mapper`：MyBatis-Flex Mapper。
- `config`：AI 模型、Redis、COS、CORS、JSON、缓存等配置。
- `monitor`：AI 模型监控指标采集。
- `ratelimter`：限流注解、切面和配置。目录名当前拼写为 `ratelimter`。

### 微服务版本

聚合工程：

- `zzx-ai-code-microservice/pom.xml`

模块：

- `zzx-ai-code-common`：公共能力。
- `zzx-ai-code-model`：模型定义。
- `zzx-ai-code-client`：内部 Dubbo 接口。
- `zzx-ai-code-user`：用户服务，HTTP 端口 `8124`，Dubbo 端口 `50051`。
- `zzx-ai-code-app`：应用服务，HTTP 端口 `8125`，Dubbo 端口 `50053`。
- `zzx-ai-code-ai`：AI 服务能力模块。
- `zzx-ai-code-screenshot`：截图服务，HTTP 端口 `8127`，Dubbo 端口 `50052`。

微服务注册中心默认使用：

- `nacos://127.0.0.1:8848`

## 前端结构

前端入口：

- `zzx-ai-code-frontend/src/main.ts`

路由：

- `/`：首页，创建应用、查看我的应用和精选应用。
- `/user/login`：用户登录。
- `/user/register`：用户注册。
- `/app/chat/:id`：应用生成对话页，包含 AI 对话和实时预览。
- `/app/edit/:id`：应用编辑页。
- `/admin/userManage`：用户管理。
- `/admin/appManage`：应用管理。
- `/admin/chatManage`：对话管理。

前端 API 基础配置：

- `zzx-ai-code-frontend/src/config/env.ts`
- 默认后端地址：`http://localhost:8123/api`
- 默认部署域名：`http://localhost`
- 静态资源地址：`${API_BASE_URL}/static`
- Vue 工程预览地址会自动追加 `dist/index.html`。

前端接口文件位于：

- `zzx-ai-code-frontend/src/api/`

这些接口文件看起来由 OpenAPI 配置生成，配置文件是：

- `zzx-ai-code-frontend/openapi2ts.config.ts`

## 核心业务流程

### 应用创建与代码生成

1. 用户在前端首页输入提示词。
2. 前端调用应用创建接口，得到应用 ID。
3. 页面跳转到 `/app/chat/:id`。
4. 前端通过 SSE 调用后端代码生成接口。
5. 后端根据代码生成类型选择生成策略。
6. AI 流式返回内容或工具调用信息。
7. 后端解析生成结果并保存代码文件。
8. 前端在右侧 iframe 中展示生成结果。

### 代码生成类型

后端枚举：

- `html`：原生 HTML 模式。
- `multi-file`：原生多文件模式。
- `vue_project`：Vue 工程模式。

前端枚举：

- `html`
- `multi_file`
- `vue_project`

注意：前后端对多文件模式的值存在差异，后端是 `multi-file`，前端是 `multi_file`。修改相关逻辑时需要特别确认转换关系。

### AI 代码生成核心类

- `AiCodeGeneratorFacade`：代码生成门面，负责选择生成模式、处理流式响应、保存代码、构建 Vue 项目。
- `AiCodeGeneratorServiceFactory`：按应用 ID 和生成类型创建 AI 服务实例。
- `CodeParserExecutor`：按生成类型解析 AI 输出。
- `CodeFileSaverExecutor`：按生成类型保存文件。
- `VueProjectBuilder`：构建生成出来的 Vue 项目。

### LangGraph4j 工作流

工作流位于：

- `src/main/java/com/zzx/zzxaicode/langgraph4j/CodeGenWorkflow.java`

主要节点：

1. `image_collector`：收集图片资源。
2. `prompt_enhancer`：增强提示词。
3. `router`：选择代码生成类型。
4. `code_generator`：生成代码。
5. `code_quality_check`：代码质量检查。
6. `project_builder`：需要时构建项目。

质量检查失败时会回到代码生成节点重新生成。

## 主要接口

单体后端主要接口前缀：

- `/api/user`：注册、登录、退出、当前用户、用户管理。
- `/api/app`：创建应用、生成代码、部署、下载、应用列表、管理端应用操作。
- `/api/chatHistory`：应用聊天历史、管理端聊天记录分页。
- `/api/chatGroup`：群聊相关能力。
- `/api/chatGroupMember`：群成员相关能力。
- `/api/static`：访问生成应用静态资源。
- `/api/workflow`：工作流执行与 SSE/Flux 流式测试。
- `/api/health`：健康检查。

应用代码生成流式接口：

- `GET /api/app/chat/gen/code`
- 返回类型：`text/event-stream`

## 数据库

建表脚本：

- `sql/create_table.sql`

核心表：

- `user`：用户。
- `app`：应用。
- `chat_history`：聊天历史。
- `chat_group`：群聊房间。
- `chat_group_member`：群成员关系。

脚本中还包含对 `chat_history` 的群聊相关字段和索引扩展：

- `groupId`
- `migrateFlag`
- `idx_single_chat`
- `idx_group_chat`
- `idx_migrateFlag`

## 本地运行

### 后端单体

要求：

- JDK 21+
- MySQL 8+
- Redis
- 必要的 AI、COS、DashScope、Pexels 等配置

常用命令：

```bash
./mvnw spring-boot:run
```

Windows 可使用：

```bash
mvnw.cmd spring-boot:run
```

默认访问：

- API：`http://localhost:8123/api`
- Knife4j：通常在 `/api/doc.html`
- Prometheus 指标：`http://localhost:8123/api/actuator/prometheus`

### 前端

```bash
cd zzx-ai-code-frontend
npm install
npm run dev
```

常用脚本：

- `npm run dev`：启动开发服务器。
- `npm run build`：类型检查并构建。
- `npm run pure-build`：仅 Vite 构建。
- `npm run type-check`：Vue 类型检查。
- `npm run lint`：ESLint 自动修复。
- `npm run format`：Prettier 格式化 `src/`。
- `npm run openapi2ts`：生成前端接口代码。

### 监控

Prometheus 配置：

- `prometheus.yml`

默认抓取：

- Prometheus 自身：`localhost:9090`
- 应用指标：`localhost:8123/api/actuator/prometheus`

Grafana 配置：

- `grafana/ai_model_grafana_config.json`

## 配置注意事项

- `application.yml` 中大量配置通过 `${zzx-ai-code...}` 占位符读取，应由本地或环境配置提供。
- 不要把数据库密码、Redis 密码、AI API Key、COS Secret、DashScope Key、Pexels Key 等敏感信息写入文档或提交到仓库。
- 微服务目录下部分 `application.yml` 可能包含本地开发配置，提交前需要检查是否有敏感值。
- README 提到线上部署时需要把 profile 从 `local` 调整为 `prod`，并检查 `AppServiceImpl` 中的应用部署目录相关代码。

## 开发注意事项

- 当前开发目标只针对单体架构项目。默认只修改根目录单体后端 `src/`、根目录 `pom.xml`、`src/main/resources/` 以及前端 `zzx-ai-code-frontend/`。
- 不要修改 `zzx-ai-code-microservice/` 下的任何文件，除非用户明确要求处理微服务版本。
- 项目存在单体版和微服务版两套相似代码，改动前要确认目标是根目录 `src/` 还是 `zzx-ai-code-microservice/` 下的某个模块。
- 生成代码、静态资源预览、部署下载等功能集中在应用服务和 `core` 包，改动时要同时关注前端 iframe 预览路径。
- AI 相关改动要关注提示词文件、模型配置、流式响应、工具调用消息格式和代码保存逻辑。
- 前端请求统一走 `src/request.ts`，未登录时会打开全局登录弹窗。
- 管理员能力依赖角色枚举和鉴权注解/拦截器，涉及权限时要同时检查后端注解和前端访问控制。
- 数据库使用逻辑删除字段 `isDelete`，删除接口通常不应物理删除数据。
- 当前仓库里 `AliyunJavaAgent/` 是未跟踪目录，属于本地运行或监控相关文件，改动前先确认是否需要纳入版本管理。

## 删除安全规范

本项目操作文件时遵守最小影响范围：

- 禁止执行递归或批量删除命令，例如 `Remove-Item -Recurse`、`rm -rf`、`rd /s`、`del /s`。
- 如确需删除，只允许一次删除一个明确完整路径的单个文件。
- 遇到删除多个文件、删除目录、通配符删除或范围不明确的请求时，应停止并要求用户逐个确认。
