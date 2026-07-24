# Label Studio 多人 NER 标注执行手册

Label Studio 是当前项目的主标注平台。本文对应 `ner-labels-v1`，覆盖 8 人分工、词典预标、双标比较、冲突仲裁和 Gold 数据冻结。Doccano 脚本只用于兼容已经产生的历史导出。

## 1. 先理解标注方式

一句 Query 可以框选多个实体；每次框选的一段文字只能选择一个标签。例如：

```text
给员工买50元以内的膳魔师保温杯
```

- `员工` → `SCENE`；
- `膳魔师` → `BRAND`；
- `保温杯` → `PRODUCT_TYPE`；
- `50元以内` 不标，由 Java 价格规则识别。

项目目前只有 5 类 NER 标签：

| 标签 | 含义 | 示例 |
|---|---|---|
| `CATEGORY` | 数据库类目树中的规范类目 | 医疗保健、护理护具 |
| `BRAND` | 平台品牌或审核后的品牌别名 | 华为、膳魔师 |
| `PRODUCT_TYPE` | Query 中的商品通用名称 | 手机、保温杯、口罩 |
| `SCENE` | 使用或采购场景 | 员工福利、送长辈 |
| `ATTRIBUTE_VALUE` | 可参与检索的属性值 | 黑色、316不锈钢、15.6英寸 |

不要创建 `O` 标签，它由训练代码自动生成；也不要自行添加 `MODEL/AUDIENCE/PRICE`。新增标签必须同步修改标签规范、schema、Java `EntityType`、转换脚本和评测集。

## 2. Label Studio 项目配置

主配置文件是 [`label-studio-config.xml`](label-studio-config.xml)。在 Label Studio 中创建空项目后进入 **Settings → Labeling Interface → Code**，粘贴完整 XML。

关键配置是：

```xml
<Labels name="entity" toName="query" choice="single">
```

`choice="single"` 表示一个框选区域只能有一个标签，不是整句话只能有一个实体。配置中的 `Text name="query"`、`Labels name="entity"` 必须与预标 JSON 的 `to_name/from_name` 完全一致。

每位成员使用独立账号，不共享管理员账号。第一阶段建议每位标注员一个项目：管理员导入该成员专属任务包，项目只加入该成员和产品标签负责人。这样不会抢任务、覆盖标注或提前看到另一人的答案。

## 3. 准备 Query 和预标任务

原始 Query 使用 UTF-8 JSONL，一行一个对象：

```json
{"query_id":"q_000001","text":"华为手机","group_id":"g_000001","source":"query_log"}
{"query_id":"q_000002","text":"给员工买50元以内的保温杯","group_id":"g_000002","source":"query_log"}
```

先脱敏、去重并设置 `group_id`。再用数据库导出的品牌、类目和属性词典生成 Label Studio 预标任务：

```powershell
python src/data_build_annotation_tasks.py `
  --queries data/raw/query-log.jsonl `
  --dictionary data/raw/dictionaries.json `
  --output data/annotation-tasks/all-label-studio.json `
  --format label-studio
```

词典或模型预标只是提示，不能直接成为 Gold。校准集和冻结 test 建议生成不含预标的任务，避免标注员被错误答案锚定。

## 4. 给 8 人确定性分工

```powershell
python src/data_assign_label_studio_tasks.py `
  --input data/annotation-tasks/all-label-studio.json `
  --output-dir data/annotation-tasks/ner-v1-batch-001 `
  --annotators product01,product02,product03,dev01,dev02,dev03,dev04,dev05 `
  --overlap-ratio 0.20 `
  --seed ner-v1-batch-001
```

输出目录包含每人一个 `.json` 文件和 `assignment-manifest.json`。清单记录任务数、双标数、每人工作量、随机种子和输入 Query ID 摘要；相同输入、人员、比例和 seed 会得到相同分配。

20% 双标任务会出现在两个人的个人项目中，并带相同的 `review_pair_id`；其余任务只出现一次。校准集、dev/test 应改用 `--overlap-ratio 1.0`，确保 100% 双标。

## 5. 导入和日常标注

管理员把 `01-product01.json` 导入 product01 的项目，以此类推。导入时选择 JSON，不要选择 JSONL/CSV。每个项目都使用同一份 `label-studio-config.xml`。

标注顺序：

1. 先阅读完整 Query；
2. 鼠标框选实体的最小完整边界；
3. 点击一个标签或使用数字快捷键；
4. 在同一句中继续框选其他实体；
5. 检查遗漏和重叠后提交；
6. 不确定的 Query 记录问题，不凭感觉增加标签。

首日 8 人共同完成 100 条校准集。两两 strict span F1 未达到 0.90 时，先统一边界规范再扩大标注量。

## 6. 自动比较双标结果

每个个人项目完成后，从 Label Studio 导出原始 JSON，保存到受控目录：

```powershell
python src/data_compare_label_studio.py `
  --input product01=data/exports/batch-001/product01.json `
  --input product02=data/exports/batch-001/product02.json `
  --input product03=data/exports/batch-001/product03.json `
  --input dev01=data/exports/batch-001/dev01.json `
  --input dev02=data/exports/batch-001/dev02.json `
  --input dev03=data/exports/batch-001/dev03.json `
  --input dev04=data/exports/batch-001/dev04.json `
  --input dev05=data/exports/batch-001/dev05.json `
  --conflicts-output data/reports/batch-001-conflicts.jsonl `
  --adjudication-output data/annotation-tasks/batch-001-adjudication.json `
  --summary-output data/reports/batch-001-summary.json
```

脚本只比较至少出现两次的 Query：

- `exact_agreement_rate`：整条 Query 所有 `start/end/label` 完全一致的比例；
- `entity_pairwise.micro_f1`：所有双标实体的严格匹配 F1；
- `conflicts.jsonl`：开发排查用的详细冲突；
- `adjudication.json`：只包含冲突 Query，可直接导入仲裁项目。

## 7. 冲突仲裁

创建一个只有产品标签负责人的仲裁项目，使用 [`label-studio-adjudication-config.xml`](label-studio-adjudication-config.xml)，导入 `batch-001-adjudication.json`。

页面会显示两位标注员的冲突摘要，但仲裁人应根据原文和冻结规范重新框选最终答案。完成后导出 JSON。若 Label Studio 版本支持 ground truth，可将最终标注设为 ground truth；否则使用 `--approved-by` 明确记录仲裁责任人。

完全一致的双标样本不需要人工重复仲裁。仲裁项目完成并导出后，运行合并工具：

```powershell
python src/data_merge_label_studio_exports.py `
  --input data/exports/batch-001/product01.json `
  --input data/exports/batch-001/product02.json `
  --input data/exports/batch-001/product03.json `
  --input data/exports/batch-001/dev01.json `
  --input data/exports/batch-001/dev02.json `
  --input data/exports/batch-001/dev03.json `
  --input data/exports/batch-001/dev04.json `
  --input data/exports/batch-001/dev05.json `
  --adjudication data/exports/batch-001/adjudication-final.json `
  --approved-by product-lead `
  --output data/exports/batch-001/merged-final.json
```

合并工具会把单标样本保留为 Silver、完全一致的双标样本标为 Gold、冲突样本替换成产品负责人的仲裁答案，并拒绝缺少仲裁的冲突。

## 8. 转换、校验和冻结

```powershell
python src/data_convert_label_studio.py `
  --input data/exports/batch-001/merged-final.json `
  --output data/gold/ner-gold-2026w30-v1.jsonl `
  --dataset-version ner-gold-2026w30-v1

python src/data_validate.py data/gold/ner-gold-2026w30-v1.jsonl
python src/data_split.py `
  data/gold/ner-gold-2026w30-v1.jsonl `
  --output-dir data/splits/ner-gold-2026w30-v1
```

未经仲裁的单人标注会转换为 `silver`；设置了唯一 ground truth 或显式 `--approved-by` 的最终数据才是 `gold`。Gold 必须保留 `approved_by`、`dataset_version`、`group_id` 和来源。

## 9. 每批验收

- 任务分配清单与原始去重数量一致；
- 双标覆盖率符合批次要求，工作量无明显失衡；
- 不存在同一 span 多标签、未知标签、越界或重叠实体；
- strict entity F1 和完全一致率被保存；
- 冲突全部仲裁，`data_validate.py` 错误数为 0；
- 原始日志、任务包、导出、Gold 和报告均不进入 Git；
- Git 只保存配置、脚本、脱敏样例和不可逆版本摘要。

## 10. Doccano 兼容边界

已有 Doccano 导出不需要丢弃，仍可使用 `data_convert_doccano.py` 转换。新批次统一从 Label Studio 流程开始，不要在同一个数据版本中混用两种平台的任务 ID、审批状态和分工方式。
