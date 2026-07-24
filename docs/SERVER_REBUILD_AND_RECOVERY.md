# 服务器重建、迁移与恢复手册

本文用于把当前 Windows + Docker Desktop 服务器迁移到原生 Linux，或在磁盘/系统故障
后从 Git、GHCR 和数据备份恢复服务。命令中的域名、网卡名和内网地址必须按实际环境
替换。

## 是否值得从 Windows 迁移

当前业务容器实际常驻内存约 4 GB，其中 Elasticsearch 占主要部分。原生 Linux 可以
去掉 Windows 桌面系统与 Docker Desktop/WSL2 的额外开销，Docker 网络、资源限制和
systemd 开机恢复也更直接。它通常会让 16 GB 机器的可用内存和运行稳定性更可预测，
但不会消除 Elasticsearch、Label Studio 或 Kibana 自身的业务内存。

建议：

- 使用 Ubuntu Server 24.04 LTS 作为保守、成熟的长期支持版本；
- 默认不启动 Kibana，只有排障或索引管理时启用 `admin` profile；
- 保持 ES 堆为 2 GB，观察至少一周的 `docker stats`、主机可用内存和磁盘 I/O；
- 数据和并发继续增长时，优先扩容到 32 GB，或把 Elasticsearch 迁到独立机器；
- 不要为了省内存禁用健康检查、持久化或备份。

## 迁移前清单

### 1. 记录现状

```powershell
docker compose --profile admin -f compose.shared.yml ps
docker compose -p ai-search-prod -f compose.apps.yml ps
docker stats --no-stream
docker images --digests
Get-NetIPConfiguration
```

记录当前域名、内网 IP、网关、DNS、Docker 版本、索引名称、Redis database、镜像 SHA
标签和健康检查结果。导出 GitHub Environment 变量的名称，但不要把 secret 值写入
仓库。

### 2. 备份必须保留的内容

- `E:\ai-search-data\elasticsearch`；
- `E:\ai-search-data\redis`；
- `E:\label-studio-data`；
- `E:\ai-search-data\files`；
- `E:\ai-search-data\ner\models\production`；
- `E:\ai-search-data\credentials`；
- `C:\ai-search-config\shared.env` 和 `prod.env`；
- Cloudflare Tunnel、宿主机 Nginx/Caddy、Windows 防火墙和计划任务配置。

日志可以按审计要求备份，但不是恢复服务的必要数据。不要只备份 Git 工作区，它不包含
数据库、标注、上传文件、模型和密钥。

### 3. 做一致性冷备份

内部服务器最稳妥的简单方案是安排维护窗口：

```powershell
docker compose -p ai-search-prod -f compose.apps.yml stop
docker compose --profile admin -f compose.shared.yml stop
```

确认所有容器停止后，把上面的目录复制到另一块磁盘或受控备份服务器，并生成 SHA-256
清单。完成复制后可先恢复旧服务。大型生产索引应改用 Elasticsearch Snapshot API，
而不是长期依赖文件级冷拷贝。

至少验证：

- 备份能在另一台机器读取；
- 文件总数、总字节数和哈希清单匹配；
- `prod.env`、`shared.env` 和凭据文件未进入 Git；
- GitHub 上存在要部署的 commit 和对应的 `sha-<40位提交>` 镜像。

## Linux 网络与安全设计

优先在路由器/DHCP 服务器上给机器设置地址保留，这比把地址写死在主机上更容易维护。
如果必须使用静态地址，先用 `ip link` 确认真实网卡名，再创建 Netplan 配置，例如：

```yaml
network:
  version: 2
  ethernets:
    enp1s0:
      dhcp4: false
      addresses:
        - 192.168.10.20/24
      routes:
        - to: default
          via: 192.168.10.1
      nameservers:
        addresses: [192.168.10.1, 1.1.1.1]
```

先执行 `sudo netplan try`，从另一台机器确认 SSH 仍可达，再执行
`sudo netplan apply`。在内部 DNS 中把服务域名解析到入口网关或这台主机。

只开放：

- TCP 22：仅管理网段或 VPN；
- TCP 443：仅公司内网、VPN 或受控隧道入口；
- 可选 ICMP：用于内网监控。

不要开放 8080、18080、19200、16379、9200 或 6379。应用网关继续绑定
`127.0.0.1:18080`，由宿主机 Caddy/Nginx 或 Cloudflare Tunnel 提供 HTTPS 入口。

## 安装 Linux 主机

### 1. 基础系统

安装 Ubuntu Server 后：

```bash
sudo apt update
sudo apt full-upgrade -y
sudo apt install -y ca-certificates curl git jq rsync openssh-server
sudo timedatectl set-timezone Asia/Shanghai
```

创建独立运维账号并配置 SSH 公钥。确认公钥登录有效后，再关闭 SSH 密码登录。不要让
GitHub Actions Runner 使用日常管理员账号。

### 2. 安装 Docker Engine

按 Docker 官方 Ubuntu 仓库安装，不使用来历不明的一键脚本：

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" |
  sudo tee /etc/apt/sources.list.d/docker.list >/dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker run --rm hello-world
```

### 3. Elasticsearch 主机参数与目录

```bash
echo 'vm.max_map_count=1048576' |
  sudo tee /etc/sysctl.d/99-ai-search-elasticsearch.conf
sudo sysctl --system

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

Elasticsearch 容器使用 uid 1000。恢复数据后按实际镜像 uid/gid 设置目录所有者；文件
服务目录需要允许容器 uid 10001 写入。不要粗暴使用 `chmod -R 777`。

## 恢复代码、配置和数据

### 1. 拉取受保护分支

```bash
sudo git clone \
  --branch master \
  https://github.com/tansong33/vemall-ai-search.git \
  /opt/ai-search
cd /opt/ai-search
git rev-parse HEAD
```

私有仓库使用只读 Deploy Key。GHCR 为私有时，使用仅有 `read:packages` 的 PAT：

```bash
echo "$GHCR_READ_TOKEN" |
  sudo docker login ghcr.io -u tansong33 --password-stdin
unset GHCR_READ_TOKEN
```

不要把 PAT 写入 shell 历史、仓库或 env 文件。

### 2. 创建仓库外环境文件

`/etc/ai-search/shared.env` 至少包含：

```dotenv
ES_DATA_DIR=/srv/ai-search/data/elasticsearch
REDIS_DATA_DIR=/srv/ai-search/data/redis
LABEL_STUDIO_DATA_DIR=/srv/ai-search/data/label-studio
FILE_SERVICE_DATA_DIR=/srv/ai-search/data/files
KIBANA_ENV_FILE=/srv/ai-search/credentials/kibana.env
ELASTICSEARCH_IMAGE=ghcr.io/tansong33/vemall-ai-search-elasticsearch:sha-REPLACE_COMMIT
FILE_SERVICE_IMAGE=ghcr.io/tansong33/vemall-ai-search-file-service:sha-REPLACE_COMMIT
ES_TUNNEL_BIND_ADDRESS=127.0.0.1
REDIS_TUNNEL_BIND_ADDRESS=127.0.0.1
```

`/etc/ai-search/prod.env` 至少包含：

```dotenv
BACKEND_IMAGE=ghcr.io/tansong33/vemall-ai-search-backend:sha-REPLACE_COMMIT
FRONTEND_IMAGE=ghcr.io/tansong33/vemall-ai-search-frontend:sha-REPLACE_COMMIT
GATEWAY_IMAGE=ghcr.io/tansong33/vemall-ai-search-gateway:sha-REPLACE_COMMIT
NER_MODEL_DIR=/srv/ai-search/data/ner/models/production
BACKEND_LOG_DIR=/srv/ai-search/logs/backend
NGINX_LOG_DIR=/srv/ai-search/logs/nginx
NGINX_HTPASSWD_FILE=/srv/ai-search/credentials/nginx.htpasswd
GATEWAY_BIND_ADDRESS=127.0.0.1
GATEWAY_PORT=18080
ES_INDEX=products_v2
REDIS_DATABASE=0
```

设置 `sudo chmod 600 /etc/ai-search/*.env`。Label Studio 公网地址、CSRF 来源、Kibana
密钥和 Nginx 密码文件也必须按实际域名配置。

### 3. 恢复数据

保持所有 Compose 服务停止，把冷备份复制到 `/srv/ai-search/data` 和 credentials 目录。
先核对哈希，再修正所有者。Elasticsearch 和 Redis 数据不能在容器运行时覆盖。

## 第一次启动与验收

```bash
cd /opt/ai-search

sudo docker compose --profile admin \
  --env-file /etc/ai-search/shared.env \
  -f compose.shared.yml pull
sudo docker compose --profile admin \
  --env-file /etc/ai-search/shared.env \
  -f compose.shared.yml up -d --no-build

sudo docker compose -p ai-search-prod \
  --env-file /etc/ai-search/prod.env \
  -f compose.apps.yml pull
sudo docker compose -p ai-search-prod \
  --env-file /etc/ai-search/prod.env \
  -f compose.apps.yml up -d --no-build
```

执行：

```bash
sudo docker compose --profile admin -f compose.shared.yml ps
sudo docker compose -p ai-search-prod -f compose.apps.yml ps
curl -fsS http://127.0.0.1:18080/health
curl -fsS http://127.0.0.1:18080/api/system/status | jq
curl -fsS http://127.0.0.1:19200/products_v2/_count | jq
```

再从另一台内网机器验证 HTTPS 首页、搜索、Label Studio、文件上传下载以及一个已知查询。
确认索引文档数、NER 版本和搜索结果符合迁移前记录。

## 开机自动恢复

仓库提供：

- `backend/deploy/systemd/ai-search-shared.service`；
- `backend/deploy/systemd/ai-search-apps.service`。

安装并启用：

```bash
sudo cp /opt/ai-search/backend/deploy/systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now ai-search-shared.service
sudo systemctl enable --now ai-search-apps.service
systemctl status ai-search-shared.service ai-search-apps.service
```

应用服务会等待共享层启动。自动部署只替换应用层镜像；ES、Redis、Label Studio 等共享
服务仍通过维护窗口升级。

## GitHub 自动部署 Runner

在仓库 Settings → Actions → Runners 新增 self-hosted Runner，并加标签
`ai-search-deploy`。Runner 应满足：

- 使用独立低权限账号，只能控制本项目的 Docker Compose；
- 只绑定这个仓库，不作为组织通用 Runner；
- 不执行 Pull Request job；
- `dev` 和 `master` 都启用分支保护；
- GitHub Environment `development` / `production` 限制部署分支，生产配置审批人；
- Repository variable `AUTO_DEPLOY_ENABLED` 在全部保护规则验收前保持未设置；准备完成
  后再设为 `true`；
- Environment variable `DEPLOY_ENV_FILE` 分别指向开发或生产 env 文件；
- Environment variable `DEPLOY_HEALTH_URL` 指向该环境的本机网关健康地址；
- Environment variable `DEPLOY_PROJECT_ROOT` 指向该环境的稳定检出目录，例如
  Windows `E:\ai-search-prod` 或 Linux `/opt/ai-search`。

Runner 拉取的是 CI 生成的不可变 `sha-<commit>` 镜像。部署脚本会备份当前三项应用镜像
引用，以 `ff-only` 把稳定目录同步到触发部署的准确 commit，更新 env，执行 `pull` 和
`up -d --no-build`，验证失败则恢复旧镜像并重新启动。稳定目录存在本地改动或无法
快进时，部署会直接停止，不会覆盖服务器文件。

公共仓库的 self-hosted Runner 风险更高。不要允许来自 fork 的 PR 在它上面运行，也不
要在 Runner 主机保存无关系统的管理员密钥。条件允许时，把 Runner 放在独立部署节点，
通过最小权限的 SSH/远程 Docker 通道操作业务主机。

## 切换与回退

1. Linux 上完成全部本机和内网验收；
2. 降低内部 DNS TTL；
3. 停止 Windows 应用层，做最后一次数据同步；
4. 启动 Linux 共享层和应用层；
5. 把域名切到新入口；
6. 观察错误率、搜索结果、内存和磁盘至少一个业务周期；
7. 保留 Windows 数据盘和配置，但不要让两套服务同时写同一个逻辑数据集。

需要回退时，先停止 Linux 应用层和共享层，把 DNS 切回 Windows，确认数据回退点是否会
丢失迁移后的新写入，再启动 Windows。Label Studio 和文件中心在切换期间应安排只读或
维护窗口，避免双写。

## 定期恢复演练

至少每季度在隔离机器上完成一次：

- 从空系统安装 Docker；
- 只用 Git commit、GHCR 镜像、仓库外 env 和备份恢复；
- 验证系统状态、索引文档数、搜索、标注和上传下载；
- 记录恢复时间目标（RTO）和可接受数据丢失窗口（RPO）；
- 更新本手册中已经失效的网络、版本、目录和联系人信息。

参考：

- [Docker Engine on Ubuntu](https://docs.docker.com/engine/install/ubuntu/)
- [Ubuntu Netplan 网络配置](https://ubuntu.com/server/docs/explanation/networking/configuring-networks/)
- [GitHub self-hosted Runner 安全](https://docs.github.com/en/actions/reference/security/secure-use)
