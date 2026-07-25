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
| `refactor/xxx` | 重构、清理、结构调整（不改行为） | 作者本人 | 同上 |
| `chore/xxx` | 依赖升级、CI 配置、文档等杂项 | 作者本人 | 同上 |
| `hotfix/xxx` | 生产紧急修复 | 作者本人 | PR 直接回 `main`（并回合 `dev`） |

> ⚠️ **当前 CI 配置与本表不一致，合并到 `main` 不会触发任何流水线。**
> `.github/workflows/ci-cd.yml` 的触发分支写的是 `master`，而本仓库的生产分支是
> `main`（远程不存在 `master`）。镜像的 `stable` 标签和 `production` 环境也都判断
> `refs/heads/master`。也就是说 §1 描述的"合并 main → 构建镜像 → 服务器部署"这条链路
> **目前断在第一步**。修好之前，`dev → main` 的 PR 合并后不会有新镜像产出。

**分支前缀就用上表这几种**，拿不准时一律用 `feature/`。注意前缀词表和第 3 节的
commit type 词表**不是同一套**：commit type 有 9 种（含 `docs`、`perf`、`ci` 等），
分支前缀只有上面 6 种。一次重构类改动的正确写法是**分支 `refactor/`，commit 用
`refactor:`**；如果嫌前缀记不住，分支统一 `feature/` 也不算错。

分支命名只用小写字母、数字、连字符和斜杠，**不要用中文、空格、逗号**（会在 CI 和
shell 脚本里出问题）。

```
feature/query-intent-classification
fix/onnx-missing-artifact
refactor/model-training-cleanup
chore/bump-elasticsearch-8-12-2
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

# 5. 合并后删掉分支，本地也清一下
git checkout dev && git pull
git branch -d feature/your-feature
```

### 什么时候可以发 PR

**分支推上去、自查通过就可以发，不用等"全部做完"。** 向 `dev` 的 PR 门槛本来就低——
第 7 节那句"只有合并到 `main` 才动生产"就是这个意思，`dev` 上的 PR 是让 CI 和 review
先跑一遍，不是终审。

判断标准是**这个分支是不是一个自洽的单元**，而不是工作量大小：

| 可以发 | 先别发 |
| --- | --- |
| 功能做完，遗留项写清楚了 | 改到一半，目录/引用处于半迁移状态 |
| 纯重构，行为不变，测试能证明 | 测试红着，且不是有意为之 |
| 本地跑不了的检查，想让 CI 跑 | 明知会引入回归，想"先合了再修" |
| 拆分过的第一个 PR，后续还有 | 一个 PR 里塞了三件不相干的事 |

本地环境缺依赖（比如没装 Python 跑不了 `pytest`）**不是延后发 PR 的理由**——CI 会跑，
这正是 PR 的价值。但要在 PR 描述里写明哪些检查你本地没跑过。

### PR 会跑哪些检查

`.github/workflows/ci-cd.yml` 在 PR 和 push 时都会跑下面四个 job，全绿才应该合：

| job | 实际执行 |
| --- | --- |
| `backend-tests` | Java 21，`mvn -f backend/pom.xml test` |
| `frontend-tests` | Node 20.19，`npm ci` + `npm run build`（构建即检查） |
| `python-tests` | Python 3.11，`pytest model-training/tests` 和 `model-training/product-ner/tests` |
| `build-images` | 构建 5 个镜像（backend / frontend / gateway / file-service / elasticsearch）。**PR 上只构建不推送**，push 到分支时才推 GHCR |

`deploy` job 不在 PR 上跑：它只在 **push** 到 `dev` 或 `master` 且仓库变量
`AUTO_DEPLOY_ENABLED == 'true'` 时触发，跑在自建 runner 上。

### dev 往前走了怎么办

分支落后于 `dev` 时，**在自己分支上 rebase**，不要把 `dev` merge 进来（避免 PR 里
混进一堆别人的 commit，review 时看不清自己改了什么）：

```bash
git checkout dev && git pull
git checkout feature/your-feature
git rebase dev
# 有冲突就解，解完 git rebase --continue
git push --force-with-lease      # 注意是 --force-with-lease，不是 --force
```

`--force-with-lease` 会在远程有你不知道的新提交时拒绝推送，比 `--force` 安全。
**只在自己的功能分支上 rebase**，`dev` 和 `main` 永远不要 rebase。

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

**scope 可加可不加。** 本仓库是多模块的，`refactor(model-training): ...` 这种写法比
裸的 `refactor: ...` 信息量大，允许使用；不加也完全合规。同一个 PR 里保持一致即可。

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

判断"一件事"的标准是**能不能单独回滚**，不是改了几个文件：一次重构可能动 30 个文件
但仍是一件事；而"顺手改了个 bug + 升了个依赖"就是两件事，哪怕只有 2 行。

正文（可选）写**为什么**，不是重复标题里的"做了什么"。删代码尤其要写清依据，
否则半年后没人敢确认还能不能删：

```
refactor: 删除未接入的 Python 推理服务

线上推理运行在 Java 进程内，主 Compose 与 CI 镜像矩阵均未引用这些文件，
其存在与既定架构相冲突。相应移除 requirements 中仅为该服务存在的依赖。
```

拆分时注意**顺序**：让每个中间态都是自洽的。比如"删旧实现 + 加新测试"，应该先提交
新测试再提交删除，反过来会出现文档引用了还不存在的东西。

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
| 更新 AI 模型 | 运维执行，不走 Git。制品契约见 [model-training/artifacts/README.md](../model-training/artifacts/README.md)，接入方式见 [product-ner/docs/java_integration.md](../model-training/product-ner/docs/java_integration.md) |
| 看线上日志 / 排障 | 运维通过 SSH 上服务器 |
| 服务器整机重建 | [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) |
| 访问 Kibana / 文件中心 | 运维入口，需网关认证，非日常开发所需 |

---

## 7. 常见问题

**Q：我 push 到 Gitee 了，为什么 GitHub / 线上没动？**
Gitee → GitHub 是定时同步（免费版有延迟）。急的话在 Gitee 仓库页面手动"立即同步"。
且只有合并到 `main` 才部署生产。push 到 `dev` 会构建并推送镜像，若仓库变量
`AUTO_DEPLOY_ENABLED` 为 `true`，还会部署到 `development` 环境——**不影响生产**。

**Q：能直接 push 到 main 吗？**
不能，`main` 应设为保护分支，仅接受 PR。直接 push 会被拒绝（未设保护时也请自觉不要）。

**Q：本地跑不起来 ES？**
不用在本地跑，用第 5 节的 SSH 隧道复用服务器的。真要本地起，用
`docker compose -f compose.yml up -d`。

**Q：提交里不小心带了 node_modules / target？**
检查 `.gitignore` 是否覆盖，已暂存的用 `git rm -r --cached <目录>` 移除后重新提交。

**Q：改动跨了几十个文件，第 4 节说不让用 `git add -A`，怎么办？**
规则的目的是防止误带文件，不是禁止批量暂存。文件多时可以 `git add -A`，但**必须**
紧接着用 `git diff --cached --stat` 和 `git diff --name-status --cached | grep '^A'`
把新增文件逐个看一遍，确认没有意外内容，再执行第 4 节剩下的密钥扫描。
心里没底就还是显式 `git add <文件>`。

**Q：合进 `dev` 的东西有问题，怎么退？**
不要用 `git reset` 改写已推送的历史（别人已经基于它开了分支）。用 `git revert`：

```bash
git checkout dev && git pull
git revert -m 1 <合并提交的 SHA>     # -m 1 表示保留 dev 这一侧
git push
```

`-m 1` 只对 merge commit 需要；如果是 squash 合并的单个提交，直接
`git revert <SHA>`。revert 之后原分支想重新合入，需要先 revert 那个 revert，
或者另开新分支重做——这也是**宁可 PR 拆小**的现实理由。

**Q：分支名写错了，已经 push 了怎么办？**
本地 `git branch -m <新名>`，然后推新名、删旧名：

```bash
git branch -m feature/correct-name
git push -u origin feature/correct-name
git push origin --delete refactor/wrong-name
```

如果 PR 已经开了，Gitee 上的 PR 会因为源分支消失而失效，需要用新分支重开一个。
**所以切分支前先对一眼第 2 节的前缀表**，比事后改省事。
