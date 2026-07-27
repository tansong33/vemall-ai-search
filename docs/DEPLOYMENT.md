# 部署与恢复

## 编排方式

`compose.shared.yml` 管理长生命周期的 Elasticsearch、Redis 和可选工具，
`compose.apps.yml` 管理可独立发版的 `ai-search-rest`、前端与网关。应用更新不会重建
共享数据服务；Kibana 仅在 `tools` profile 下启动：

```bash
docker compose -f compose.shared.yml --profile tools up -d
```

`compose.yml` 用于单机完整部署，`compose.staging.yml` 用于与共享栈并行验收。生产环境
复用既有 ES/Redis 时只启动应用栈，不启动本仓库的 shared 单元。`AI_SEARCH_SHARED_NETWORK`
应指向既有 Docker 网络；若依赖通过远程地址访问，则先创建一个同名应用网络：

```bash
docker network inspect ai-search-shared >/dev/null 2>&1 || docker network create ai-search-shared
```

## 环境变量

真实环境文件放在仓库外，例如 `/etc/ai-search/prod.env`。

| 变量 | 用途 |
| --- | --- |
| `ES_HOST` / `ES_PORT` / `ES_SCHEME` / `ES_INDEX` | ES 地址、协议和只读商品索引 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | Redis 连接信息 |
| `NER_MODE` / `NER_MODEL_ENABLED` / `NER_MODEL_VERSION` | NER 模式、模型开关与版本 |
| `NER_MODEL_DIR` | 宿主机 ONNX 制品目录 |
| `SEARCH_INDEX_VERSION` / `SEARCH_DICT_VERSION` | 索引、词典缓存版本 |
| `SEARCH_NER_MODEL_VERSION` / `SEARCH_RULE_VERSION` | 模型、规则缓存版本 |
| `AI_SEARCH_REST_IMAGE` / `FRONTEND_IMAGE` / `GATEWAY_IMAGE` | 应用镜像 |
| `AI_SEARCH_REST_LOG_DIR` / `NGINX_LOG_DIR` | 日志目录 |
| `ES_DATA_DIR` / `REDIS_DATA_DIR` | 共享栈数据目录 |
| `GATEWAY_BIND_ADDRESS` / `GATEWAY_PORT` | 网关监听地址和端口 |

密码、Token、私钥和真实服务地址不得提交；Compose 中仅保留变量与本地安全默认值。
生产环境显式设置四个缓存版本；更新模型时同步递增 `NER_MODEL_VERSION` 和
`SEARCH_NER_MODEL_VERSION`。
启动 gateway 前，`NGINX_HTPASSWD_FILE` 必须指向已创建的普通文件；认证内容由运维在
仓库外生成和保管。

## 数据目录与挂载

Ubuntu 推荐布局：

```text
/srv/ai-search/
├── data/elasticsearch/
├── data/redis/
├── models/ner/
├── logs/rest/
└── logs/nginx/
```

ES 数据挂到 `/usr/share/elasticsearch/data`，Redis 数据挂到 `/data`，NER 模型以只读
方式挂到 `/app/models/ner`，应用日志挂到 `/var/log/ai-search`。首次启动前创建目录，
并按镜像内进程的实际 uid/gid 设置所有者；不要凭文档猜 uid。

## 发布

```bash
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env config
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env pull
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env up -d --no-build
curl -fsS http://127.0.0.1:18080/actuator/health
```

CI 使用不可变的 `sha-<commit>` 镜像标签。回滚时恢复上一版环境文件中的三个应用镜像，
再次执行 `pull` 和 `up -d --no-build`；共享层数据不随应用回滚。

## 备份

复用外部生产 ES/Redis 时，本应用不得停止、改权限或恢复它们的数据目录。ES 索引由外部
数据流程备份；Redis 仅保存可重建缓存。这里只备份仓库外环境文件、NER 制品、网关凭据
及其校验和，凭据备份须加密保存。

仅当本部署拥有 `compose.shared.yml` 的数据时，才执行以下步骤：

1. 停止应用写入并停止 shared 中的 ES/Redis。
2. 冷备数据目录、环境文件、NER 制品和凭据；保留 ACL、扩展属性和数字 uid/gid。
3. 大型 ES 集群优先使用 Snapshot API，并记录索引名、mapping、文档数和快照状态。
4. 为归档生成 SHA-256 校验和，复制到异机或对象存储，定期隔离演练。

不能热拷贝正在写入的 Lucene 目录。即使本应用只读，备份窗口也须与外部写入方协调。

## 恢复

1. 安装与原环境兼容的 Docker，取回对应 commit 的 Compose 文件和不可变镜像。
2. 校验备份；外部依赖场景只恢复应用配置、NER 制品和凭据，不恢复 ES/Redis。
3. 自有 shared 场景保持容器停止后恢复数据，用镜像内 `id` 核对 uid/gid，禁止
   `chmod 777`，再启动共享层。
4. 检查 ES 集群状态、mapping 和文档数，执行 `redis-cli PING`。
5. 启动应用层，检查 `/actuator/health`，再用两个 API 做只读冒烟测试。

若 ES 报段损坏，不要在原备份上直接修复；保留副本并优先恢复更早的一致快照。

## 索引数据导入

商品索引的创建、全量导入和增量同步由外部流程负责，本仓库不提供 MySQL 同步任务，也不
在应用启动时写入索引。部署前由数据负责人完成导入并核对 mapping、别名和文档数，随后把
`ES_INDEX` 指向已验收索引。应用账号只需该索引的读取权限。
