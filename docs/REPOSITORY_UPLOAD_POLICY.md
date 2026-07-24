# 仓库上传范围与敏感文件检查

本仓库只保存能够复现系统的源码、测试、Compose 配置、部署脚本和不含真实凭据的示例。
运行数据、密钥、训练数据和构建产物必须留在服务器数据盘、备份介质或专用制品库。

## 提交到仓库的内容

- `backend`、`frontend`、`model-training` 和 `services` 的源码、测试与说明；
- `model-training/product-ner` 中的训练工具、配置和小型脱敏样例；
- Dockerfile、Compose 文件、Nginx 配置、CI/CD 和运维脚本；
- `.env.example` 中的占位值和安全默认值；
- README 与 `docs` 中的架构、部署和恢复说明。

## 明确不提交的内容

| 类别 | 示例 | 保存位置 |
| --- | --- | --- |
| 历史或导入来源 | 根目录下的 `ai-search/`、`product-ner/`、`product-ner-training/` | 本地参考目录，确认无用后单独归档 |
| 真实配置和凭据 | `.env`、`.env.prod`、`.env.shared`、密码、Token、私钥、`nginx.htpasswd`、`kibana.env` | Windows `E:\ai-search-data\credentials` 或 Linux `/etc/ai-search` |
| 运行数据 | Elasticsearch、Redis、Label Studio、上传文件和日志 | Windows `E:\ai-search-data`、`E:\label-studio-data`；Linux `/srv/ai-search` |
| 训练数据 | 原始、Gold、Silver、切分后的数据集 | 外部数据盘或受控对象存储 |
| 模型制品 | ONNX、PyTorch 权重、checkpoint、训练报告和交付压缩包 | 模型制品库或外部数据盘 |
| 本地构建与缓存 | `node_modules`、`dist`、`target`、`.venv`、`__pycache__`、`.pytest_cache` | 可由依赖锁文件和源码重新生成 |

不要把 `E:\ai-search-data`、`E:\label-studio-data`、`E:\ai-search-next-data`、
`C:\ai-search-config` 或 `/srv/ai-search` 复制进工作区。若必须提交新的样例数据，应先
脱敏、缩小规模，并确认不存在客户、账号、商品内部字段或个人信息。

## 环境文件规则

`.env.example` 只能包含占位值、公开地址和安全默认值。实际环境使用仓库外文件：

- Windows：`C:\ai-search-config\shared.env`、`C:\ai-search-config\prod.env`；
- Linux：`/etc/ai-search/shared.env`、`/etc/ai-search/prod.env`。

镜像标签、普通端口和数据目录可以出现在示例中；密码、PAT、Cookie、私钥和真实加密
密钥不可以。GitHub Actions 的部署凭据放在 GitHub Environment secrets 中。

## 每次推送前检查

在仓库根目录执行：

```powershell
git status --short
git diff --check
git diff --cached --stat
git ls-files
git check-ignore -v ai-search product-ner product-ner-training frontend/node_modules backend/target
```

确认即将提交的文件后，再检查常见秘密特征：

```powershell
git diff --cached | Select-String -Pattern `
  'BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|ghp_[A-Za-z0-9]+|github_pat_|AKIA[0-9A-Z]{16}|password\s*=\s*[^<\s]'
```

如果发现秘密已经进入提交历史，不能只删除当前文件：应先撤销或轮换凭据，再使用经过
审查的历史清理流程。任何疑似敏感文件在确认前都不要 `git add -A`；优先按目录和文件
显式暂存。
