# 协作规范与提交流程

本文约定分支模型、提交信息规范、PR 流程、发版与自动部署，以及本地开发如何复用
服务器的共享服务。所有成员在提交代码前请通读一遍。

配套文档：
- 能不能提交某个文件 → [REPOSITORY_UPLOAD_POLICY.md](REPOSITORY_UPLOAD_POLICY.md)

---

## 1. 仓库与镜像的关系（先理解这个）

本项目是**镜像化交付**，不是"服务器拉代码运行"：

```
开发者 push Gitee
  → Gitee 自动同步到 GitHub（镜像仓库，仅供 CI）
  → GitHub Actions 构建 5 个镜像 → 推送 GHCR，打不可变标签 sha-<40位commit>
  → 服务器轮询 main 分支，发现新 commit 且镜像就绪 → 拉镜像部署 → 健康检查 → 失败回滚
```

要点：

- **服务器从不构建、从不改代码**。它只拉现成镜像。
- **谁都不要在服务器上 `git push` 或改文件**。服务器的检出目录只允许快进更新，
  有本地改动会导致自动部署直接失败。
- 所有开发在**自己电脑**上进行。

---

## 2. 分支模型

| 分支 | 作用 | 谁能写 | 触发 |
| --- | --- | --- | --- |
| `main` | 生产分支，线上运行的版本 | **禁止直接 push**，仅 PR 合入 | 合入即触发服务器自动部署 |
| `dev` | 集成分支，日常功能汇合 | 通过 PR 合入 | 触发 CI 构建镜像，不部署生产 |
| `feature/xxx` | 新功能 | 作者本人 | PR 时触发 CI 测试 |
| `fix/xxx` | 缺陷修复 | 作者本人 | 同上 |
| `hotfix/xxx` | 生产紧急修复 | 作者本人 | PR 直接回 `main`（并回合 `dev`） |

分支命名只用小写字母、数字、连字符和斜杠，**不要用中文、空格、逗号**（会在 CI 和
shell 脚本里出问题）。

```
feature/query-intent-classification
fix/onnx-missing-artifact
hotfix/gateway-503
```

### 日常流程

```bash
# 1. 从最新 dev 切分支
git checkout dev && git pull
git checkout -b feature/your-feature

# 2. 开发、提交（提交信息见第 3 节）
git add <显式文件>
git commit -m "feat: 增加查询意图分类"

# 3. 推到 Gitee
git push -u origin feature/your-feature

# 4. 在 Gitee 上发起 PR: feature/your-feature → dev
#    通过 review 和 CI 后合并
```

### 发版流程（dev → main）

```
dev 积累若干功能且验收通过
  → 在 Gitee 发起 PR: dev → main
  → 负责人 review + 批准
  → 合并到 main
  → 服务器 5 分钟内轮询到，自动拉镜像部署
```

**只有向 `main` 合并才会动生产。** 平时在 `dev` 上再怎么折腾都不影响线上。

---

## 3. 提交信息规范（Conventional Commits）

格式：`<type>: <简短描述>`，描述用中文或英文均可，一行讲清楚做了什么。

| type | 用于 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 修复缺陷 |
| `docs` | 文档 |
| `refactor` | 重构（不改行为） |
| `perf` | 性能优化 |
| `test` | 测试 |
| `build` | 构建、依赖、Dockerfile |
| `ci` | CI/CD 配置 |
| `chore` | 杂项（不影响 src/test） |

```
feat: 增加查询意图分类
fix: 处理 ONNX 制品缺失时的降级
docs: 更新部署流程
refactor: 拆分召回服务
build: 升级 elasticsearch 镜像至 8.12.2
```

**破坏性变更**在 type 后加 `!`，并在正文说明：

```
feat!: 搜索接口响应结构调整

原 results 字段拆分为 items 和 aggregations，前端需同步适配。
```

一次提交只做一件事。混在一起的大提交难 review、难回滚。

---

## 4. 每次推送前检查（必做）

在仓库根目录执行，确认没有把密钥、数据、构建产物带进去。

**Linux / macOS：**

```bash
git status --short
git diff --check                    # 检查冲突标记、行尾空白
git diff --cached --stat            # 即将提交的文件一览
git ls-files | grep -iE '\.env$|htpasswd|kibana\.env|\.pem$' | grep -v example
# ↑ 有输出说明混入了敏感文件，立即 git rm --cached 移除

# 扫描暂存区里的常见密钥特征
git diff --cached | grep -nE \
  'BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|ghp_[A-Za-z0-9]+|github_pat_|AKIA[0-9A-Z]{16}|password[[:space:]]*=[[:space:]]*[^<[:space:]]'
```

**Windows PowerShell：**

```powershell
git status --short
git diff --check
git diff --cached --stat
git ls-files | Select-String -Pattern '\.env$|htpasswd|kibana\.env|\.pem$' |
  Where-Object { $_ -notmatch 'example' }

git diff --cached | Select-String -Pattern `
  'BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|ghp_[A-Za-z0-9]+|github_pat_|AKIA[0-9A-Z]{16}|password\s*=\s*[^<\s]'
```

**不要用 `git add -A` / `git add .` 一把梭**，容易带进意外文件。优先按文件显式暂存。

如果敏感信息**已经进了提交历史**：不能只删文件（历史里还在）。先**轮换/作废该凭据**
（改密码、吊销 token、重新生成 `kibana.env` / `htpasswd`），再走历史清理流程。

---

## 5. 本地开发复用服务器 ES / Redis

不必在本地起 ES/Redis。通过 SSH 隧道复用服务器共享实例（服务器上这两个端口只绑
`127.0.0.1`，不对外开放）。两种连法：

```bash
# 在外网：经 Cloudflare Tunnel 的 SSH 入口
# 本机需装 cloudflared（Windows: winget install Cloudflare.cloudflared；
# macOS: brew install cloudflared），首次连接弹浏览器做邮箱验证
ssh -N \
  -L 19200:127.0.0.1:19200 \
  -L 16379:127.0.0.1:16379 \
  -o ProxyCommand="cloudflared access ssh --hostname ssh.tsong.xyz" \
  <user>@ssh.tsong.xyz
```

> 外网 SSH 入口由 Cloudflare Access 保护，只有白名单邮箱能发起连接。需要访问权限
> 找运维在 Access 策略里加邮箱。

隧道建立后，本地后端指向回环端口：

```bash
cd backend
ES_HOST=127.0.0.1 ES_PORT=19200 REDIS_HOST=127.0.0.1 REDIS_PORT=16379 \
  mvn spring-boot:run
```

### 多人共用同一实例的隔离约定

Redis 逻辑库只避免键冲突，**不是安全隔离**。严格隔离需独立实例。约定：

| 环境 | `ES_INDEX` | `REDIS_DATABASE` |
| --- | --- | --- |
| 生产 | `products_v2` | `0` |
| 开发 | `products_v2_dev` | `1` |

- 共享 ES **不做破坏性测试**（删索引、重建 mapping）。
- 集成测试用带 `_dev` 后缀的环境索引；单元测试用 Mock 或临时索引。

---

## 6. 权限与访问速查

| 想做的事 | 怎么做 |
| --- | --- |
| 改代码 / 文档 | 自己电脑 clone，改完 PR，**不在服务器上改** |
| 本地连服务器 ES 调试 | 第 5 节的 SSH 隧道 |
| 触发一次生产部署 | PR 合并到 `main`，自动进行 |
| 更新 AI 模型 | 见 [DEPLOYMENT.md](DEPLOYMENT.md)，运维执行，不走 Git |
| 看线上日志 / 排障 | 运维通过 SSH 上服务器，见 DEPLOYMENT.md |
| 访问 Kibana / 文件中心 | 运维入口，需网关认证，非日常开发所需 |

---

## 7. 常见问题

**Q：我 push 到 Gitee 了，为什么 GitHub / 线上没动？**
Gitee → GitHub 是定时同步（免费版有延迟）。急的话在 Gitee 仓库页面手动"立即同步"。
且只有合并到 `main` 才部署生产，`dev` 只构建镜像。

**Q：能直接 push 到 main 吗？**
不能，`main` 应设为保护分支，仅接受 PR。直接 push 会被拒绝（未设保护时也请自觉不要）。

**Q：本地跑不起来 ES？**
不用在本地跑，用第 5 节的 SSH 隧道复用服务器的。真要本地起，用
`docker compose -f compose.yml up -d`。

**Q：提交里不小心带了 node_modules / target？**
检查 `.gitignore` 是否覆盖，已暂存的用 `git rm -r --cached <目录>` 移除后重新提交。
