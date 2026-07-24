# Label Studio 项目设置建议

版本：Label Studio 1.23.x（2026-03 发布的稳定版）

## 创建项目
1. `pip install label-studio==1.23.0`（独立虚拟环境，**不要**和训练环境混装，它会拉一堆旧依赖）
2. `label-studio start --data-dir E:\ai-search-data\label-studio`
3. Project → Settings → Labeling Interface → Code → 粘贴 `labeling_config.xml`

## 关键设置（逐项都有理由）
| 设置 | 值 | 理由 |
|---|---|---|
| Annotation → Show predictions to annotators | **ON** | 预标注是提速的全部意义；关掉等于从零标 |
| Annotation → Overlap of annotations | 1（gold 批次设 2） | 测试集必须双标 + 仲裁 |
| Annotation → Require label before submit | OFF | 允许"本条无实体"，这是合法样本 |
| Quality → Skip queue | Requeue for others | 跳过的样本不能悄悄消失 |
| Instructions | 贴 `docs/label_spec.md` | 边界规则必须在标注器里随时可见 |
| Task Sampling | Sequential | 保证批次可复现、可审计 |

## 导入 / 导出
- 导入：Project → Import → 上传 `weak_label.py` 产出的 `import_*.json`（带 `predictions`）
- 导出：Project → Export → **JSON**（不要用 JSON-MIN，它会丢掉 `annotations` 里的元信息）
- 导出文件放 `data/label_studio/export_<batch>_<date>.json`，进 git-lfs 或对象存储，**不要**覆盖旧文件

## 双标与仲裁
gold（测试集）批次：Overlap=2，两位标注者独立标，用 `validate_annotations.py` 比对后由第三人仲裁。
项目里可以直接看 Agreement，但最终真值以仲裁后的单一 annotation 为准（`convert_label_studio.py --strategy last`）。
