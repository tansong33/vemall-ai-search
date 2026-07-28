# 运维手册

给接手的人或 AI 助手看。**先读完「不要做的事」再动手。**

## 连接

```bash
ssh pink@100.114.218.91          # Tailscale 地址，走内网 192.168.31.47 通常不通
```

本机 `pink` 已在 docker 组，所有 `docker`/`docker compose` 命令**不需要 sudo**。
但 `/etc/ai-search/` 只有 root 可读，改那里的文件必须 sudo 且需要交互式密码。

## 正常状态

8 个容器，分属两个 Compose 项目：

```bash
docker ps --format '{{.Names}}\t{{.Status}}'
```

| 项目 | 容器 | 说明 |
| --- | --- | --- |
| `ai-search-shared` | elasticsearch、redis、kibana、label-studio、file-service | **有状态**，发版时不动 |
| `ai-search-prod` | ai-search-rest、frontend、gateway | 无状态，整体替换即发版 |

对外只有网关的 `127.0.0.1:18080`，公网经 Cloudflare Tunnel 到 `ai-search.tsong.xyz`。

## 服务器重启之后

**两个 systemd 单元都已 enabled，正常情况下开机自动拉起，不需要人工介入。**

```bash
systemctl status ai-search-shared ai-search-apps
```

两个都 `active (exited)` 就是正常的（`Type=oneshot` + `RemainAfterExit=yes`）。
任一 `failed`，先看日志再动手：

```bash
journalctl -u ai-search-apps -b --no-pager | tail -30
```

### 已知会导致开机拉不起来的原因

systemd 读的是 **`/etc/ai-search/prod.env`**，不是 `/opt/ai-search/.env`。
两个文件不同步时，compose 会退回 `compose.apps.yml` 里的默认镜像名
（`ai-search-rest:1.0.0` 之类），而**这些镜像并不存在**——镜像目前是在服务器本地
构建、用 commit sha 打标签的，不在任何 registry 里。于是 compose 去 Docker Hub 拉，
403，整个服务启动失败，站点 502。

改完 `/etc/ai-search/prod.env` 后**必须**验证解析结果，不要只看命令有没有报错：

```bash
docker compose -f /opt/ai-search/compose.apps.yml \
  --env-file /etc/ai-search/prod.env config | grep 'image:'
```

三行都应是本地实际存在的 tag。用 `docker images | grep ai-search` 核对。

## 手动启停

```bash
# 推荐：走 systemd，状态与开机行为一致
sudo systemctl restart ai-search-apps
sudo systemctl stop    ai-search-apps

# 应急：直接用 compose（systemd 会认为服务仍是 failed，事后补一次 restart）
cd /opt/ai-search
docker compose -f compose.apps.yml --env-file .env -p ai-search-prod up -d
```

共享层同理，把 `apps` 换成 `shared`、env 换成 `/etc/ai-search/shared.env`。
**共享层非必要不要重启**——Elasticsearch 里有 109 万条真实数据。

## 验证服务确实好了

不要只看容器是不是 `Up`，要打真实请求：

```bash
G=http://127.0.0.1:18080
curl -s -o /dev/null -w '%{http_code}\n' $G/actuator/health     # 200
curl -s -o /dev/null -w '%{http_code}\n' $G/search/             # 200
printf '{"query":"手机","page":1,"pageSize":20}' > /tmp/q.json
curl -s -H 'Content-Type: application/json; charset=utf-8' \
     --data-binary @/tmp/q.json $G/api/search | head -c 200
```

`success=true` 且 `total` 不为 0 才算好。公网再验一次
`https://ai-search.tsong.xyz/search/`——网关通不等于隧道通。

### 别把网关的 404 当故障

网关只认已定义的路径，其余一律 404，这是有意的。
`/backend-health`、`/api/search/pipeline` 这类旧路由现在返回 404 属正常。
**如果发现随便乱写的路径都返回 200，那才是配置坏了**（`location /` 被改回了
`try_files` 兜底），会把已下线的路由伪装成健康的。

## 故障排查顺序

| 现象 | 先查 |
| --- | --- |
| 站点 502 | `docker ps` 看应用栈在不在；不在就看 `journalctl -u ai-search-apps -b` |
| 容器起不来，日志说 `No such image` | env 文件里的镜像 tag 与本地镜像对不上，见上文 |
| ES 容器反复重启 | `sysctl vm.max_map_count`（应为 1048576，已在 `/etc/sysctl.d/99-ai-search-elasticsearch.conf` 持久化）；再查数据目录属主是否 uid 1000 |
| 上传文件报 500 | `/srv/ai-search/data/files` 属主必须是 uid 10001，读和健康检查正常不代表能写 |
| 搜索返回 0 条 | 索引没了或 `ES_INDEX` 指错：`curl 127.0.0.1:19200/_cat/indices?v` |
| 搜索 503 | ES 不可用且无缓存。这是如实上报，不是 bug |

## 不要做的事

- **不要为了测降级去停真实的 ES 或 Redis**。要测就起一次性容器指向不存在的主机名，
  发布到临时端口验证，用完 `docker rm -f`。
- **不要把 `GATEWAY_BIND_ADDRESS` 改成 `0.0.0.0`**。它必须只绑回环，公网入口由隧道负责。
- **不要往仓库里提交任何凭据**。真实环境文件在仓库外（`/etc/ai-search/*.env`），
  compose 里只保留变量名和安全的本地默认值。
- **不要修改已标注数据或标签集**。三份标签集当前不一致是已知的，
  统一涉及既有标注结果，未经标注团队确认不得改。
- **不要假设 `/opt/ai-search` 是最新代码**。它目前是重构前的旧 checkout，
  只有 `compose.apps.yml` 和 `.env` 被手工更新过。要改代码走本地仓库重新构建镜像。

## 当前的临时状态

以下几条是权宜之计，不是设计意图，看到时不要以为是规范：

- 镜像在服务器上本地构建（`docker build -t ai-search-rest:<sha> .`），不在 registry 里。
  **换一台机器就没有这些镜像。** 正常应由 CI 构建并推 GHCR，见 `.github/workflows/ci-cd.yml`。
- `/opt/ai-search` 是旧代码 checkout，与仓库 HEAD 不一致。
- 回滚镜像保留在本地：`ghcr.io/tansong33/vemall-ai-search-{backend,frontend,gateway}:sha-77bc4a9e…`，
  备份在 `/home/pink/cutover-backup-20260727-142244/`。
