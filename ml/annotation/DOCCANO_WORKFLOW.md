# Doccano 多人 NER 标注执行手册（历史兼容）

当前项目已将 Label Studio 设为主标注平台，主流程见 [`LABEL_STUDIO_WORKFLOW.md`](LABEL_STUDIO_WORKFLOW.md)。本手册仅用于兼容已经在 Doccano 中创建的任务和导出；新批次不要在两种平台之间混用任务 ID 和审批状态。真实 Query 和导出文件不得提交 Git。

## 1. 固定标签

Doccano 项目类型选择 **Sequence Labeling**，只创建下面 5 个标签：

| 标签 | 含义 | 示例 |
|---|---|---|
| `CATEGORY` | 数据库类目树中的规范类目 | 医疗保健、护理护具 |
| `BRAND` | 平台品牌或审核后的品牌别名 | 华为、膳魔师 |
| `PRODUCT_TYPE` | 用户说出的商品通用名称 | 手机、保温杯、口罩 |
| `SCENE` | 使用或采购场景 | 员工福利、送长辈 |
| `ATTRIBUTE_VALUE` | 可被检索的属性值 | 316不锈钢、黑色、15.6英寸 |

价格、数量、SKU/条码、排序诉求和否定词先由 Java 规则解析，不在 v1 NER 标签中临时新增标签。新增标签必须先修改标签规范、JSON schema、Java `EntityType`、转换脚本和评测集，不能只改 Doccano 页面。

## 2. 项目和人员组织

不要让 8 个人同时在一个无分工的任务池里自由领取。第一阶段使用“每位标注员一个项目”的简单方式：

- 管理员：创建项目、导入该成员专属文件、导出结果；
- 标注员：只进入自己的项目；
- 产品标签负责人：查看冲突清单，在单独的“仲裁项目”里确定最终答案；
- 开发负责人：运行本目录脚本、校验数据、冻结版本。

双标样本会被脚本同时分给两个人，并带相同的 `review_pair_id`。其余样本只出现一次，因此不存在两人保存时互相覆盖的问题。校准集、dev/test 应使用 100% 双标；大批训练集起步可用 20% 双标加 10% 随机复审。

## 3. 准备原始 Query

原始文件为 UTF-8 JSONL，一行一个 JSON 对象，不是 Excel 文本、Python 字典或 JSON 数组：

```json
{"query_id":"q_000001","text":"华为手机","group_id":"g_000001","source":"query_log"}
{"query_id":"q_000002","text":"给员工买50元以内的保温杯","group_id":"g_000002","source":"query_log"}
```

先脱敏和去重；真实 `query_id/session_id` 需按项目 README 的方式加盐哈希。`group_id` 必须把同一原句的改写、同一模板批次或同一会话归为一组，防止训练集泄漏到测试集。

## 4. 生成预标任务并分配

先用数据库导出的规范品牌、类目、属性字典做候选预标。预标只是加速提示，人工必须逐字检查：

```powershell
python ml/src/data_build_annotation_tasks.py `
  --queries ml/data/raw/query-log.jsonl `
  --dictionary ml/data/raw/dictionaries.json `
  --output ml/data/annotation-tasks/all.jsonl `
  --format doccano

python ml/src/data_assign_doccano_tasks.py `
  --input ml/data/annotation-tasks/all.jsonl `
  --output-dir ml/data/annotation-tasks/ner-v1-batch-001 `
  --annotators product01,product02,product03,dev01,dev02,dev03,dev04,dev05 `
  --overlap-ratio 0.20 `
  --seed ner-v1-batch-001
```

输出目录包含每人一个 JSONL 和 `assignment-manifest.json`。清单记录任务数、双标数、每人工作量、种子和输入 Query ID 摘要；重新运行同一命令会得到相同分配。管理员把 `01-product01.jsonl` 导入 product01 的项目，以此类推。

在 Doccano 导入页选择 **JSONL**。如果出现 `Expecting value`、`property name enclosed in double quotes` 或 `Extra data`，说明文件不是“一行一个、双引号包围字段名”的合法 JSONL；不要直接上传 `.txt`、CSV、Python 字典或整个 JSON 数组。

## 5. 标注规则和提速方法

1. 首日 8 人共同标同一批 100 条校准集，产品负责人集中解释冲突；两两 strict span F1 达到 0.90 后再扩量。
2. 规范品牌和类目来自数据库候选词典，但标注的是 Query 中实际出现的字符范围，不能把 Query 没出现的数据库规范词硬标进去。
3. “荣耀MagicBook 15”可标 `荣耀=BRAND`，其余是否为产品类型或属性按冻结手册判断；不要在标注时凭感觉新建 `MODEL`。
4. 高频确定样本使用快捷键；歧义、错别字、否定和词典匹配不上时打评论或记入问题表，不要猜。
5. 每 200～300 条集中处理一次问题，更新手册后再继续；不要每遇到一条就在群里打断全部人员。
6. 预标只用于训练集提速；校准集和冻结 test 建议不显示预标，避免锚定偏差。

Label Studio 或自研页面不能自动解决“Query 短语如何映射到百万词库”的语义歧义。正确做法是先从品牌/类目/属性表构建 Top-K 候选，再由人工确认 span 和标签；规范 ID 映射属于后续实体链接模块，不应强塞进第一版 NER span 标注。

## 6. 导出并自动比较双标结果

每个项目标完后导出 Doccano JSONL，文件放在本地受控目录，例如 `ml/data/exports/batch-001/`。运行：

```powershell
python ml/src/data_compare_doccano.py `
  --input product01=ml/data/exports/batch-001/product01.jsonl `
  --input product02=ml/data/exports/batch-001/product02.jsonl `
  --input product03=ml/data/exports/batch-001/product03.jsonl `
  --input dev01=ml/data/exports/batch-001/dev01.jsonl `
  --input dev02=ml/data/exports/batch-001/dev02.jsonl `
  --input dev03=ml/data/exports/batch-001/dev03.jsonl `
  --input dev04=ml/data/exports/batch-001/dev04.jsonl `
  --input dev05=ml/data/exports/batch-001/dev05.jsonl `
  --conflicts-output ml/data/reports/batch-001-conflicts.jsonl `
  --summary-output ml/data/reports/batch-001-summary.json
```

脚本只比较在至少两个导出中出现的 Query，单标任务计入 `singleton_queries_ignored`。严格一致要求每个实体的 `start/end/label` 全部相同；冲突清单会区分文本、标签和边界问题。产品标签负责人只需处理 `conflicts.jsonl`，一致样本无需重复查看。

## 7. 仲裁、转换和冻结

把冲突样本导入单独的仲裁项目，产品负责人决定最终结果；再将所有最终答案汇总成一个“一条 Query 只有一行”的导出文件。Doccano 版本若不导出审批人字段，可用 `--approved-by` 明确记录本批次的人工仲裁责任人：

```powershell
python ml/src/data_convert_doccano.py `
  --input ml/data/exports/batch-001/final-adjudicated.jsonl `
  --output ml/data/gold/ner-gold-2026w30-v1.jsonl `
  --approved-by product-lead `
  --dataset-version ner-gold-2026w30-v1

python ml/src/data_validate.py ml/data/gold/ner-gold-2026w30-v1.jsonl
python ml/src/data_split.py `
  ml/data/gold/ner-gold-2026w30-v1.jsonl `
  --output-dir ml/data/splits/ner-gold-2026w30-v1
```

未经仲裁的单份导出可以转换，但只能得到 `quality_level=silver`。`--require-approved` 会拒绝没有审批字段的记录。`--allow-doccano-id-fallback` 只用于遗失 `meta.query_id` 的旧数据；Doccano 自增 ID 跨项目不稳定，不应作为正常流程。

## 8. 每批验收

- `assignment-manifest.json` 的输入任务数与原始去重数一致；
- 双标覆盖率符合批次要求，标注员工作量无明显失衡；
- 比较脚本的 strict entity pairwise F1 与完全一致率被保存；
- 冲突全部仲裁，`data_validate.py` 错误数为 0；
- Gold 包含 `approved_by` 和不可变 `dataset_version`；
- 原始日志、分配文件、导出、Gold 和报告均不进入 Git，只提交脱敏样例和版本摘要。
