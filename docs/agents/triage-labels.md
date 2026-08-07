# 分诊标签

工程技能使用五种标准分诊角色。本文件将这些标准角色映射到当前仓库问题跟踪器中的实际标签。

| 标准角色 | GitHub 标签 | 含义 |
| --- | --- | --- |
| `needs-triage` | `needs-triage` | 维护者尚未评估该问题 |
| `needs-info` | `needs-info` | 正在等待报告者补充必要信息 |
| `ready-for-agent` | `ready-for-agent` | 信息和验收标准完整，可以交给智能体实现 |
| `ready-for-human` | `ready-for-human` | 需要人工判断、授权或实现 |
| `wontfix` | `wontfix` | 已决定不处理 |

当某个技能提到标准角色，例如“应用适合智能体处理的标签”时，使用上表中对应的 GitHub 标签。

如果将来仓库改用其他标签命名，只需修改本表的“GitHub 标签”列，避免技能创建重复或语义冲突的标签。
