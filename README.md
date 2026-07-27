# AI 智能搜索

面向中文电商搜索，使用 NER 实体识别与 Elasticsearch 混合字段召回提供商品检索。

## 架构

```text
ai-search-fccapi/    对外搜索与调试契约，不依赖其他模块
ai-search-feign/     外部服务调用占位模块
ai-search-server/    NER、Query 理解、搜索编排与数据访问
ai-search-rest/      Spring Boot 入口、接口实现与对象转换
ai-search-scheduled/ 定时任务占位模块
```

调用方向固定为 `rest → server.service → server.dao → Elasticsearch/Redis`；Vue 前端和
`model-training/product-ner` 分别负责交互界面与离线模型训练。

## 快速开始

要求本机已安装 Docker Compose、JDK 8、Maven、Node.js 20.19+ 和 npm。

1. 起依赖：`docker compose -f compose.shared.yml up -d elasticsearch redis`
2. 跑后端：`mvn -q -B -DskipTests package && ES_HOST=127.0.0.1 ES_PORT=19200 REDIS_HOST=127.0.0.1 REDIS_PORT=16379 java -jar ai-search-rest/target/ai-search-rest-1.0.0-SNAPSHOT.jar`
3. 跑前端：`npm --prefix frontend ci && npm --prefix frontend run serve`

前端默认地址为 `http://localhost:8081`。停止依赖可执行
`docker compose -f compose.shared.yml down`；该命令不删除数据卷。

## 接口

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/search` | 商品搜索 |
| `POST /api/debug/pipeline` | 搜索链路调试 |
| `GET /actuator/health` | 健康检查 |

完整请求、响应与错误码见 [docs/API.md](docs/API.md)。

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `ES_HOST` | `localhost` | Elasticsearch 主机 |
| `ES_PORT` | `9200` | Elasticsearch HTTP 端口 |
| `REDIS_HOST` | `localhost` | Redis 主机 |
| `REDIS_PORT` | `6379` | Redis 端口 |
| `NER_MODE` | `hybrid` | `dictionary` / `model` / `hybrid` |
| `NER_MODEL_ENABLED` | `false` | 是否加载 ONNX 模型 |

部署时通过环境变量指向远程 Elasticsearch 和 Redis；不要把地址、账号或凭据写入仓库。

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [API 契约](docs/API.md)
- [部署与恢复](docs/DEPLOYMENT.md)
- [Git 协作流程](docs/GIT_WORKFLOW.md)
