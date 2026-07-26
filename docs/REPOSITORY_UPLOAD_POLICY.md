# 仓库上传范围与敏感文件检查

本仓库只保存能够复现系统的源码、测试、Compose 配置、部署脚本和不含真实凭据的示例。
运行数据、密钥、训练数据和构建产物必须留在服务器数据盘、备份介质或专用制品库。

推送前的具体检查命令见 [GIT_WORKFLOW.md 第 4 节](GIT_WORKFLOW.md)。

## 提交到仓库的内容

- `backend`、`frontend`、`model-training` 和 `services` 的源码、测试与说明；
- `model-training/product-ner` 中的训练工具、配置和小型脱敏样例；
- Dockerfile、Compose 文件、Nginx 配置、CI/CD 和运维脚本；
- `.env.example` 中的占位值和安全默认值；
- README 与 `docs` 中的架构、部署、协作和恢复说明。

## 明确不提交的内容

| 类别 | 示例 | 保存位置 |
| --- | --- | --- |
| 历史或导入来源 | 根目录下的 `ai-search/`、`product-ner/`、`product-ner-training/` | 本地参考目录，确认无用后单独归档 |
| 真实配置和凭据 | `.env`、`.env.prod`、`.env.shared`、密码、Token、私钥、`nginx.htpasswd`、`kibana.env`、`gateway-admin.txt`、**大模型 API Key（`LLM_API_KEY`）** | 服务器 `/etc/ai-search` 与 `/srv/ai-search/credentials` |
| 运行数据 | Elasticsearch、Redis、Label Studio、上传文件和日志 | 服务器 `/srv/ai-search` |
| 训练数据 | 原始、Gold、Silver、切分后的数据集 | 外部数据盘或受控对象存储 |
| 模型制品 | ONNX、PyTorch 权重、checkpoint、训练报告和交付压缩包 | 模型制品库或外部数据盘 |
| 本地构建与缓存 | `node_modules`、`dist`、`target`、`.venv`、`__pycache__`、`.pytest_cache` | 可由依赖锁文件和源码重新生成 |

不要把 `/srv/ai-search`、`/etc/ai-search` 或任何包含真实数据、凭据的目录复制进工作区。
若必须提交新的样例数据，应先脱敏、缩小规模，并确认不存在客户、账号、商品内部字段或
个人信息。

## 环境文件规则

`.env.example` 只能包含占位值、公开地址和安全默认值。实际环境使用仓库外文件：

- `/etc/ai-search/shared.env`
- `/etc/ai-search/prod.env`

镜像标签、普通端口和数据目录可以出现在示例中；密码、PAT、Cookie、私钥、真实加密
密钥和大模型 API Key 不可以。CI 的部署凭据放在 CI 平台的 secrets 中，不进仓库。

大模型 API Key 泄露会直接产生费用，且外部厂商的调用记录不受本项目控制。
一旦误提交，必须**先到供应商控制台吊销该 Key**，再走历史清理流程。

## 若敏感信息已进入历史

不能只删除当前文件——历史提交里仍然存在。正确顺序：

1. **立即轮换/作废该凭据**：改密码、吊销 Token、重新生成 `kibana.env` / `nginx.htpasswd`；
2. 再走经过审查的 Git 历史清理流程（`git filter-repo` 等）；
3. 若仓库有镜像同步（Gitee→GitHub），确认镜像仓库中的历史也已处理。

预防永远比清理便宜：提交前务必执行 [GIT_WORKFLOW.md 第 4 节](GIT_WORKFLOW.md)的检查，
不要用 `git add -A` 一把梭。
