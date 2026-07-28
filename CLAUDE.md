# 给 AI 助手的项目须知

## 环境

- Java 8，**不能用** `var`、`record`、`List.of`、`Map.of`、`String.isBlank`、文本块。
- Elasticsearch 服务端 8.12，客户端仍是 HLRC 7.17，靠 `compatible-with=7` 请求头兼容。
- Python 环境 `ai-search` 用 3.11（被 onnxruntime 1.26.0 的 wheel 反向锁死，不是 3.10）。

## 动服务器之前

**先读 [docs/RUNBOOK.md](docs/RUNBOOK.md)**，里面有连接方式、启停命令、排障顺序，
以及一份「不要做的事」——其中几条踩过就得停服恢复，不是风格建议。

最常踩的一条：systemd 读 `/etc/ai-search/prod.env`，不是 `/opt/ai-search/.env`。
两者不同步会导致开机拉起失败、站点 502，而容器列表看起来只是「没启动」。

## 改代码

- 分层依赖单向：`rest → server.service → server.dao → ES/Redis`。DAO 不反向依赖 service。
- `fccapi` 里每个接口一个文件夹，恰好三个文件：接口、入参、出参。
- DAO 层**吞掉所有 Redis 异常并返回 null**，缓存故障不得中断搜索。
  因此「Redis 是否可用」由写入操作的返回值判定，不要改成读操作抛异常。
- 归一化**只新增字段，绝不覆盖实体原文和字符偏移**，下游裁剪依赖原始 offset。

## 验收

`mvn clean verify` 必须全绿（当前 43 个测试）。
声称「部署好了」之前要打真实请求验证，容器 `Up` 不等于服务可用；
网关未定义路径返回 404 是正常的，**随便乱写的路径返回 200 才是配置坏了**。
