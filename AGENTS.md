## Agent skills

### Issue tracker

本仓库的问题、规格和实现任务统一跟踪在 GitHub Issues。详见 `docs/agents/issue-tracker.md`。

### Triage labels

本仓库采用默认的五类分诊标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human` 和 `wontfix`。详见 `docs/agents/triage-labels.md`。

### Domain docs

本仓库采用单上下文领域文档布局：根目录使用一个 `CONTEXT.md`，架构决策记录在 `docs/adr/`。详见 `docs/agents/domain.md`。

### 设备验收

执行 instrumentation、设备或模拟器烟雾验收，或汇报设备门禁与发布状态前，必须完整读取
`PROJECT_MEMORY.md` 的“测试与设备验收”章节。默认门禁只覆盖当前选定设备；兼容性矩阵按该章节的
非阻塞和默认静默规则处理。
