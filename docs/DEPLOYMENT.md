# 部署与恢复

## 首次部署走一遍

面向第一次在服务器上部署的人。以 Ubuntu 为例，其余发行版同理。

### 1. 前置检查

```bash
docker --version              # 需要 Docker Engine 20.10+
docker compose version        # 需要 Compose v2
free -h                       # 建议 ≥ 8 GB 可用内存（ES 自己就要 2-4 GB）
df -h /srv                    # 建议 ≥ 50 GB 可用磁盘
```

当前用户需要在 `docker` 组里，否则每条命令都要 `sudo`：

```bash
sudo usermod -aG docker "$USER" && newgrp docker
docker ps                     # 不报权限错误即可
```

### 2. 准备目录

```bash
sudo mkdir -p /srv/ai-search/{data/elasticsearch,data/redis,models/ner,logs/rest,logs/nginx}
sudo chown -R "$USER":"$USER" /srv/ai-search
```

ES 容器内以 uid 1000 运行，若数据目录属主不对会启动失败。用镜像内的 `id` 核对，
**不要凭文档猜 uid**：

```bash
docker run --rm docker.elastic.co/elasticsearch/elasticsearch:8.12.0 id
sudo chown -R 1000:1000 /srv/ai-search/data/elasticsearch
```

### 3. 准备环境文件

真实环境文件放在**仓库外**，例如 `/etc/ai-search/prod.env`：

```bash
sudo mkdir -p /etc/ai-search
sudo cp .env.example /etc/ai-search/prod.env
sudo chmod 600 /etc/ai-search/prod.env
sudo vi /etc/ai-search/prod.env
```

至少要改的几项：

```ini
ES_DATA_DIR=/srv/ai-search/data/elasticsearch
REDIS_DATA_DIR=/srv/ai-search/data/redis
NER_MODEL_DIR=/srv/ai-search/models/ner
AI_SEARCH_REST_LOG_DIR=/srv/ai-search/logs/rest
NGINX_LOG_DIR=/srv/ai-search/logs/nginx

# 缓存版本：任一变更都会让旧缓存自然失效，上线时显式设置，不要留 unknown
SEARCH_INDEX_VERSION=products-20260726-001
SEARCH_DICT_VERSION=dictionary-96244
SEARCH_NER_MODEL_VERSION=none
SEARCH_RULE_VERSION=exclusion-v2

# 网关只绑回环，对外由反向代理或隧道接管；勿改 0.0.0.0
GATEWAY_BIND_ADDRESS=127.0.0.1
GATEWAY_PORT=18080
```

### 4. 启动共享层

```bash
docker compose -f compose.shared.yml --env-file /etc/ai-search/prod.env up -d
docker compose -f compose.shared.yml ps          # 等到 STATUS 全是 healthy
curl -fsS localhost:19200/_cluster/health | head -c 200
```

ES 首次启动约需 30–60 秒。若一直 unhealthy，先看日志：
`docker compose -f compose.shared.yml logs --tail 50 elasticsearch`。

Kibana 只在 `tools` profile 下启动，默认不起：

```bash
docker compose -f compose.shared.yml --profile tools up -d
```

### 5. 导入索引数据

**这一步不做，搜索会返回 0 条。** 见下方[索引数据导入](#索引数据导入)。
导入后核对：

```bash
curl -s "localhost:19200/_cat/indices?v"                 # 确认索引存在
curl -s "localhost:19200/${ES_INDEX}/_count"             # 确认文档数符合预期
```

### 5.5 关于构建耗时

若在服务器上从源码构建（而非拉 CI 镜像），首次构建的绝大部分时间花在拉 Docker Hub 的
基础镜像上 —— 实测国内网络约 35 分钟，其中 `maven:3.9-eclipse-temurin-8` 就有 500 MB 级。
Maven 依赖下载可用镜像加速：

```bash
docker build --build-arg MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public -t ai-search-rest:local .
```

实测 Maven Central 约 146 KB/s、阿里云约 11 MB/s，加了这个参数后 Maven 阶段从数十分钟
降到约 2 分钟。不传该参数则走 Maven Central，行为与原来一致（境外 CI 无需改动）。

基础镜像本身的加速需要在 `/etc/docker/daemon.json` 配 `registry-mirrors`，但改动需要
重启 docker 守护进程；若已设 `live-restore: true`，运行中的容器不会被重启。

### 6. 启动应用层

```bash
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env config   # 先校验
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env pull
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env up -d --no-build
```

### 7. 验收

```bash
# 健康检查，ES 与 Redis 都应为 UP
curl -fsS http://127.0.0.1:18080/actuator/health

# 搜索冒烟（请求体用文件传，避免终端编码问题）
echo '{"query":"手机","page":1,"pageSize":20}' > /tmp/q.json
curl -s -X POST http://127.0.0.1:18080/api/search \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @/tmp/q.json | head -c 400

# 链路调试：应能看到 NER 实体与配件排除词
echo '{"query":"手机","includeEsDsl":true}' > /tmp/d.json
curl -s -X POST http://127.0.0.1:18080/api/debug/pipeline \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @/tmp/d.json | head -c 600
```

预期：`success=true`、`items` 非空、调试接口的 `es.mustNotClauses` 里能看到
「手机壳」「手机膜」这类排除词。

### 8. 可选：预热热搜词缓存

```bash
curl -s -X POST http://127.0.0.1:18080/api/ops/cache/warmup
```

热搜词表在 `ai-search-server/src/main/resources/hot-queries.txt`。
也可设 `SEARCH_WARMUP_ON_STARTUP=true` 让应用启动时异步预热。

---

## 编排方式

`compose.shared.yml` 管理长生命周期的 Elasticsearch、Redis 和可选工具，
`compose.apps.yml` 管理可独立发版的 `ai-search-rest`、前端与网关。应用更新不会重建
共享数据服务。

`compose.yml` 用于单机完整部署，`compose.staging.yml` 用于与共享栈并行验收。

**复用既有 ES/Redis 时只启动应用栈**，不启动本仓库的 shared 单元。
`AI_SEARCH_SHARED_NETWORK` 应指向既有 Docker 网络；若依赖通过远程地址访问，
则先创建一个同名应用网络：

```bash
docker network inspect ai-search-shared >/dev/null 2>&1 || docker network create ai-search-shared
```

## 环境变量

| 变量 | 用途 |
| --- | --- |
| `ES_HOST` / `ES_PORT` / `ES_SCHEME` / `ES_INDEX` | ES 地址、协议和只读商品索引 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` / `REDIS_DATABASE` | Redis 连接信息 |
| `NER_MODE` / `NER_MODEL_ENABLED` / `NER_MODEL_VERSION` | NER 模式、模型开关与版本 |
| `NER_MODEL_DIR` | 宿主机 ONNX 制品目录 |
| `SEARCH_INDEX_VERSION` / `SEARCH_DICT_VERSION` | 索引、词典缓存版本 |
| `SEARCH_NER_MODEL_VERSION` / `SEARCH_RULE_VERSION` | 模型、规则缓存版本 |
| `SEARCH_WARMUP_ON_STARTUP` | 启动时是否异步预热热搜词缓存 |
| `AI_SEARCH_REST_IMAGE` / `FRONTEND_IMAGE` / `GATEWAY_IMAGE` | 应用镜像 |
| `AI_SEARCH_REST_LOG_DIR` / `NGINX_LOG_DIR` | 日志目录 |
| `ES_DATA_DIR` / `REDIS_DATA_DIR` | 共享栈数据目录 |
| `GATEWAY_BIND_ADDRESS` / `GATEWAY_PORT` | 网关监听地址和端口 |

密码、Token、私钥和真实服务地址不得提交；Compose 中仅保留变量与本地安全默认值。
生产环境显式设置四个缓存版本；更新模型时同步递增 `NER_MODEL_VERSION` 和
`SEARCH_NER_MODEL_VERSION`。启动 gateway 前，`NGINX_HTPASSWD_FILE` 必须指向已创建的
普通文件；认证内容由运维在仓库外生成和保管。

## 数据目录与挂载

```text
/srv/ai-search/
├── data/elasticsearch/     → 容器内 /usr/share/elasticsearch/data
├── data/redis/             → 容器内 /data
├── models/ner/             → 容器内 /app/models/ner（只读）
├── logs/rest/              → 容器内 /var/log/ai-search
└── logs/nginx/
```

首次启动前创建目录，并按镜像内进程的实际 uid/gid 设置所有者。

## 发布

```bash
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env config
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env pull
docker compose -f compose.apps.yml --env-file /etc/ai-search/prod.env up -d --no-build
curl -fsS http://127.0.0.1:18080/actuator/health
```

CI 使用不可变的 `sha-<commit>` 镜像标签。回滚时恢复上一版环境文件中的三个应用镜像，
再次执行 `pull` 和 `up -d --no-build`；共享层数据不随应用回滚。

## 排障

| 现象 | 排查方向 |
| --- | --- |
| ES 容器反复重启 | 数据目录属主不是 uid 1000；或 `vm.max_map_count` 太小（`sudo sysctl -w vm.max_map_count=262144`） |
| `/actuator/health` 报 ES DOWN | 容器内解析不到 `ES_HOST`；确认应用与 ES 在同一 Docker 网络，或远程地址可达 |
| 搜索返回 0 条 | 索引没导入，或 `ES_INDEX` 指错。`curl localhost:19200/_cat/indices?v` 核对 |
| 搜索返回 503 + `SEARCH_UNAVAILABLE` | ES 不可用且无可用缓存。这是**如实上报**而非静默返回空结果 |
| 结果里混入配件（手机壳等） | 该品类未在 `category-specific-exclusions.txt` 登记；通用后缀只对已登记品类生效 |
| 调试页 NER 实体为空 | 该词不在词典中，或走了缓存。调试接口已强制跑完整链路，若仍为空即词典未覆盖 |
| 请求返回 400 且 message 提到 JSON | 请求体不是 UTF-8。Windows 终端下用文件传参：`--data-binary @q.json` |
| 日志里 `REDIS_UNAVAILABLE` | Redis 挂了但搜索仍可用（降级生效）。修 Redis 即可恢复缓存加速 |
| 前端根路径 404 | 正常。前端在 `/search/` 下提供服务，根路径不提供内容 |
| 容器一直 `starting` 不转 `healthy` | 看 `docker inspect <容器> -f '{{range .State.Health.Log}}{{.Output}}{{end}}'`。健康检查命令在容器内用 `/bin/sh`（dash），不支持 `/dev/tcp` 之类 bash 特性 |
| 搜「手机」仍混入手机壳 | 检查 `SEARCH_EXCLUSION_ANALYZER` 是否与索引侧 `title` 的 analyzer 一致，不一致会漏排约四成（见 ARCHITECTURE 相应章节） |

查看应用日志：

```bash
docker compose -f compose.apps.yml logs --tail 100 ai-search-rest
tail -f /srv/ai-search/logs/rest/ai-search-rest.log
```

日志行首的 `[requestId]` 可用于串联一次请求的全部日志。

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

应用依赖以下字段，导入方需保证 mapping 一致：

| 字段 | 类型 | 用途 |
| --- | --- | --- |
| `title` | text，`analyzer: ik_max_word` | 主检索字段、高亮、配件排除的 must_not 目标 |
| `brand_name` | text + keyword | 品牌 MUST 匹配、品牌聚合 |
| `category_name` | text + keyword | 品类匹配、品类聚合 |
| `model_no` | text | 型号 MUST 匹配 |
| `tags` / `attributes` | text | 修饰词、属性 SHOULD 加分 |
| `price` | double（元） | 排序与价格区间筛选，应用侧按分换算 |
| `sales_count` / `rating` | numeric | function_score 排序因子 |
| `in_stock` | boolean | 库存筛选 |
| `spu_id` / `sku_id` | keyword | SPU 去重与结果标识 |
| `main_pic` | keyword | 商品图 |

缺字段不会导致报错，但对应的排序、筛选或聚合会失效。

**`title` 的分词器配置直接影响配件排除效果。** 若 mapping 里 `search_analyzer` 与
`analyzer` 不同（例如索引用 `ik_max_word`、查询用 `ik_smart`），必须把
`SEARCH_EXCLUSION_ANALYZER` 设成索引侧那个，否则 `match_phrase` 因词元位置对不齐而
大量漏排。现网 `products_v2` 正是这种配置，实测漏排约 39%。核对方式：

```bash
curl -s "localhost:19200/${ES_INDEX}/_mapping/field/title"
curl -s -X POST localhost:19200/_analyze -H 'Content-Type: application/json'   -d '{"analyzer":"ik_max_word","text":"手机壳"}'
```
