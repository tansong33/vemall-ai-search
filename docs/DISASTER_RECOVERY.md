# 灾难恢复手册

当服务器磁盘或系统故障后，从 **Git（compose 文件）+ 镜像仓库 + 数据备份** 重建服务。

本文假设目标环境为 **Ubuntu Server（原生 Linux）+ Docker Engine**，镜像来自 GHCR
（`ghcr.io/tansong33/vemall-ai-search-*`）。命令中的域名、网卡名、内网地址、commit
按实际替换。

> 本文专注**故障后带数据恢复**。

---

## 0. 恢复前先确认手上有什么

缺一不可，缺哪个先补哪个：

- [ ] **数据备份**：`elasticsearch/`、`redis/`、`label-studio/`、`files/`、
      `ner/models/production/` 目录，以及 `credentials/`（`kibana.env`、
      `nginx.htpasswd`、`gateway-admin.txt`）。
- [ ] **要恢复到的 commit**：40 位 SHA，且 GHCR 上存在对应的 5 个 `sha-<commit>` 镜像。
- [ ] **镜像仓库访问**：GHCR 公开则免登录；私有则需 `read:packages` 的 PAT。

> **备份一致性**：Elasticsearch / Redis 的文件级备份应在**容器停止**后制作。
> 运行中热拷贝可能损坏（写到一半的段文件、未落盘的 translog）。热备份能恢复但有风险，
> 大数据量应改用 Elasticsearch Snapshot API。

---

## 1. 基础系统

```bash
sudo apt update && sudo apt full-upgrade -y
sudo apt install -y ca-certificates curl git jq rsync openssh-server unrar
sudo timedatectl set-timezone Asia/Shanghai
```

创建运维账号、配置 SSH 公钥、验证公钥登录后再关闭密码登录。

**Elasticsearch 内核参数**（缺失会导致 ES 启动失败）：

```bash
echo 'vm.max_map_count=1048576' | sudo tee /etc/sysctl.d/99-ai-search-elasticsearch.conf
sudo sysctl --system
```

---

## 2. Docker Engine

按 Docker 官方 Ubuntu 仓库安装（国内网络不通时可换清华/阿里云的 docker-ce 镜像源，
新 LTS 尚未同步时可临时用上一个 LTS 代号，二进制兼容）：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
  https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo ${UBUNTU_CODENAME:-$VERSION_CODENAME}) stable" |
  sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
```

配置日志轮转（避免容器日志撑满磁盘）：

```bash
sudo tee /etc/docker/daemon.json > /dev/null <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "50m", "max-file": "3" },
  "live-restore": true
}
EOF
sudo systemctl restart docker
```

---

## 3. 目录树

```bash
sudo install -d -m 0750 /opt/ai-search /etc/ai-search
sudo install -d -m 0750 \
  /srv/ai-search/data/elasticsearch \
  /srv/ai-search/data/redis \
  /srv/ai-search/data/label-studio \
  /srv/ai-search/data/files \
  /srv/ai-search/data/ner/models/production \
  /srv/ai-search/logs/backend \
  /srv/ai-search/logs/nginx \
  /srv/ai-search/credentials
```

---

## 4. 拉取 compose 文件

只需要仓库里的 compose 与 deploy 脚本，不需要构建：

```bash
sudo install -d -o "$USER" -g "$USER" /opt/ai-search
git clone --branch main https://gitee.com/jump20020718/ai-search.git /opt/ai-search
cd /opt/ai-search && git rev-parse HEAD
```

GHCR 私有时登录（公开可跳过）：

```bash
read -rsp "GHCR PAT: " T && echo
echo "$T" | docker login ghcr.io -u tansong33 --password-stdin
unset T
```

---

## 5. 写仓库外 env

镜像五个变量填**同一个 commit**。资源限制按机器内存调整，下列为 16 GB 机器参考值。

```bash
COMMIT=<40位SHA>
GHCR=ghcr.io/tansong33/vemall-ai-search

sudo tee /etc/ai-search/shared.env > /dev/null <<EOF
ELASTICSEARCH_IMAGE=${GHCR}-elasticsearch:sha-${COMMIT}
FILE_SERVICE_IMAGE=${GHCR}-file-service:sha-${COMMIT}
KIBANA_IMAGE=docker.elastic.co/kibana/kibana:8.12.0
REDIS_IMAGE=redis:7-alpine
LABEL_STUDIO_IMAGE=heartexlabs/label-studio:1.23.0

ES_DATA_DIR=/srv/ai-search/data/elasticsearch
REDIS_DATA_DIR=/srv/ai-search/data/redis
LABEL_STUDIO_DATA_DIR=/srv/ai-search/data/label-studio
FILE_SERVICE_DATA_DIR=/srv/ai-search/data/files
KIBANA_ENV_FILE=/srv/ai-search/credentials/kibana.env

ES_JAVA_OPTS=-Xms2g -Xmx2g
ES_MEMORY_LIMIT=4g
KIBANA_MEMORY_LIMIT=1536m
LABEL_STUDIO_MEMORY_LIMIT=1536m
REDIS_MEMORY_LIMIT=768m
FILE_SERVICE_MEMORY_LIMIT=256m

ES_TUNNEL_BIND_ADDRESS=127.0.0.1
ES_TUNNEL_PORT=19200
REDIS_TUNNEL_BIND_ADDRESS=127.0.0.1
REDIS_TUNNEL_PORT=16379

LABEL_STUDIO_HOST=https://ai-search.tsong.xyz/label-studio
LABEL_STUDIO_CSRF_TRUSTED_ORIGINS=https://ai-search.tsong.xyz,http://127.0.0.1:18080,http://localhost:18080
KIBANA_PUBLIC_URL=https://ai-search.tsong.xyz/kibana
FILE_SERVICE_MAX_UPLOAD_BYTES=1073741824
EOF

sudo tee /etc/ai-search/prod.env > /dev/null <<EOF
BACKEND_IMAGE=${GHCR}-backend:sha-${COMMIT}
FRONTEND_IMAGE=${GHCR}-frontend:sha-${COMMIT}
GATEWAY_IMAGE=${GHCR}-gateway:sha-${COMMIT}

BACKEND_LOG_DIR=/srv/ai-search/logs/backend
NGINX_LOG_DIR=/srv/ai-search/logs/nginx
NER_MODEL_DIR=/srv/ai-search/data/ner/models/production
NGINX_HTPASSWD_FILE=/srv/ai-search/credentials/nginx.htpasswd

GATEWAY_BIND_ADDRESS=127.0.0.1
GATEWAY_PORT=18080

BACKEND_JAVA_OPTS=-Xms384m -Xmx768m -XX:+UseG1GC
BACKEND_MEMORY_LIMIT=1g
FRONTEND_MEMORY_LIMIT=128m
GATEWAY_MEMORY_LIMIT=128m

ES_INDEX=products_v2
REDIS_DATABASE=0

NER_ONNX_ENABLED=false
NER_MODE=dictionary
NER_MODEL_VERSION=none
EOF

sudo chmod 600 /etc/ai-search/*.env
```

> 若 `kibana.env` / `nginx.htpasswd` 从备份恢复（下一步），**不要重新生成**：
> - `kibana.env` 密钥变了，已保存的 Kibana 仪表板/告警将无法解密；
> - `nginx.htpasswd` 换了，`gateway-admin.txt` 里记录的密码就对不上。

---

## 6. 恢复数据

**所有容器保持停止**，把备份复制进 `/srv/ai-search/data` 和 `credentials`，
核对哈希后再修所有者。

```bash
# 示例：从解压好的备份目录 $SRC 搬入
for d in elasticsearch redis files ner; do
  sudo rm -rf "/srv/ai-search/data/$d"
  sudo mv "$SRC/$d" "/srv/ai-search/data/$d"
done
sudo cp -a "$SRC/credentials/." /srv/ai-search/credentials/
```

### 所有者（uid/gid 以镜像实测为准）

```bash
# 实测：docker run --rm --entrypoint id <image>
#   elasticsearch → uid=1000 gid=0
#   file-service  → uid=10001 gid=999
sudo chown -R 1000:0    /srv/ai-search/data/elasticsearch /srv/ai-search/data/ner /srv/ai-search/logs
sudo chown -R 10001:999 /srv/ai-search/data/files
sudo chown -R 999:999   /srv/ai-search/data/redis
sudo chown -R 1001:1001 /srv/ai-search/data/label-studio
sudo chmod 600 /srv/ai-search/credentials/*
sudo chmod 750 /srv/ai-search/credentials
```

**不要用 `chmod -R 777`。** 权限过宽 ES 会拒绝启动或有安全风险。

---

## 7. 启动与验收

```bash
cd /opt/ai-search
SH="sudo docker compose --env-file /etc/ai-search/shared.env -f compose.shared.yml"
AP="sudo docker compose -p ai-search-prod --env-file /etc/ai-search/prod.env -f compose.apps.yml"

# 共享层（Kibana 按需，此处不带 --profile admin）
$SH pull
$SH up -d --no-build elasticsearch redis file-service
$SH logs -f elasticsearch      # 看到 started 且无 Corrupt/AccessDenied 后 Ctrl+C

# ES 数据校验（关键：确认索引和文档数）
curl -s http://127.0.0.1:19200/_cluster/health | jq
curl -s http://127.0.0.1:19200/_cat/indices?v

# 应用层
$AP pull
$AP up -d --no-build
curl -fsS http://127.0.0.1:18080/health && echo
curl -fsS http://127.0.0.1:18080/api/system/status | jq
```

验收标准：ES 集群 `green`/`yellow`（单节点无副本为 yellow，正常），
`products_v2` 文档数与故障前记录一致，`/api/system/status` 中 backend / ES / redis 均
up。再从内网另一台机器验证 HTTPS 首页与一次已知查询。

**ES 起不来的常见原因**：
- `AccessDeniedException` → 数据目录属主不对，回第 6 步 chown；
- `max virtual memory areas too low` → `vm.max_map_count` 未设，回第 1 步；
- `CorruptIndexException` → 热备份损坏。尝试 `elasticsearch-shard remove-corrupted-data`
  （会丢部分数据），或从更早的一致备份恢复。

---

## 8. 恢复开机自启与自动部署

```bash
# systemd 开机自启（去掉写死的 --profile admin，避免开机连 Kibana 一起起）
sudo cp /opt/ai-search/backend/deploy/systemd/ai-search-*.service /etc/systemd/system/
sudo sed -i 's/ --profile admin//g' /etc/systemd/system/ai-search-shared.service
sudo systemctl daemon-reload
sudo systemctl enable ai-search-shared.service ai-search-apps.service
```

主机电源策略（笔记本合盖不休眠）、定时重启、Cloudflare Tunnel、轮询自动部署的完整配置
见 [DEPLOYMENT.md](DEPLOYMENT.md)。

---

## 9. 恢复后清单

- [ ] `products_v2` 文档数正确，一次已知查询结果符合预期；
- [ ] NER 状态符合预期（`/api/admin/ner/status`），模型五件套齐全才开启 ONNX；
- [ ] 域名从公网可访问，运维入口（Kibana/文件中心）有认证保护；
- [ ] 开机自启已 enable，重启一次验证服务自动恢复；
- [ ] **补一份异地备份**——单盘环境下数据与备份同盘等于没有备份。

---

## 10. 定期演练（建议每季度）

在隔离机器上，仅用 Git（compose）+ GHCR 镜像 + 仓库外 env + 数据备份完整走一遍本文，
记录恢复耗时（RTO）与可接受数据丢失窗口（RPO），并更新本文中已失效的版本、路径、命令。
