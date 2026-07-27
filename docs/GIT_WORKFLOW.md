# Git 协作流程

## 分支

| 分支 | 用途 |
| --- | --- |
| `main` | 可部署的生产版本，只接收 PR |
| `dev` | 日常集成分支，只接收通过检查的 PR |
| `feature/<topic>` | 新功能 |
| `fix/<topic>` | 缺陷修复 |
| `refactor/<topic>` | 不改变契约的重构 |
| `docs/<topic>` / `chore/<topic>` | 文档或工程维护 |
| `hotfix/<topic>` | 从 `main` 切出的生产紧急修复 |

普通改动从最新 `dev` 切分支，合入目标为 `dev`。发版由 `dev → main` PR 完成；紧急修复
合入 `main` 后必须再同步回 `dev`。分支保持短生命周期，避免多人长期共用一个功能分支。

## 提交信息

使用 Conventional Commits：

```text
<type>(<scope>): <简短说明>
```

`type` 使用 `feat`、`fix`、`refactor`、`docs`、`test`、`build`、`ci` 或 `chore`。
一次提交只表达一个可回滚意图；正文说明原因、行为变化和兼容性，破坏性变化必须标注
`BREAKING CHANGE`。不要提交构建产物、运行数据、模型权重或真实凭据。

## 发起 PR

满足以下条件后再发 PR：

- 目标范围已完成，没有临时文件、mock、被禁用测试或无关格式化。
- Maven 测试、前端构建和本任务相关检查已在本地通过。
- 已检查 `git diff --check`、`git status --short` 和待提交 diff。
- 环境文件只有占位值；密码、Token、私钥、内部地址和客户数据均不在提交中。
- PR 说明包含动机、主要改动、验证结果、配置或部署影响及回滚方式。

作者不得绕过评审或 CI。评审意见解决后再合并；若 `dev` 已前进，先更新分支并重新跑检查。
合并后删除远端功能分支。

## 仓库边界

仓库只保存可复现系统所需的源码、测试、文档、Compose、Dockerfile、CI 与部署脚本。
以下内容留在外部数据盘、制品库或密钥管理系统：

- `.env`、密码、访问令牌、私钥和网关认证文件；
- Elasticsearch/Redis 数据、日志和备份；
- 原始训练数据、标注数据、ONNX/PyTorch 权重与 checkpoint；
- `target`、`dist`、`node_modules`、虚拟环境和工具缓存。

凭据若误入历史，应先立即吊销或轮换，再由负责人审批历史清理；只删除当前文件不能消除
泄露。禁止为清理历史自行强推共享分支。

## 回滚

尚未合并的改动在功能分支修正。已合入共享分支的代码使用新的 `revert` 提交并走 PR，
保留可审计历史，不重写 `main` 或 `dev`。

生产故障优先把 `AI_SEARCH_REST_IMAGE`、`FRONTEND_IMAGE` 和 `GATEWAY_IMAGE` 恢复为
上一组已验证的不可变标签，再重新部署应用层；数据库或索引变更使用事先评审的反向步骤。
回滚后补充故障原因、影响范围和长期修复 PR。
