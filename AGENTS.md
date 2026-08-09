## Agent skills

### Issue tracker

本仓库的问题、规格和实现任务统一跟踪在 GitHub Issues。详见 `docs/agents/issue-tracker.md`。

### Triage labels

本仓库采用默认的五类分诊标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human` 和 `wontfix`。详见 `docs/agents/triage-labels.md`。

### Domain docs

本仓库采用单上下文领域文档布局：根目录使用一个 `CONTEXT.md`，架构决策记录在 `docs/adr/`。详见 `docs/agents/domain.md`。

### 设备验收

执行 instrumentation、设备或模拟器烟雾验收，或汇报设备门禁与发布状态前，必须完整读取
`PROJECT_MEMORY.md` 的“测试与设备验收”章节以及 `docs/testing/device-journeys.md`。默认阻塞门禁是
当前选定设备上的完整 `@CoreJourney` 分组及 `verifyAndArchiveCoreJourney`，不是
通用 debug instrumentation 任务，也不是代理手工烟雾检查。`@SystemInteraction`、人工烟雾和兼容性矩阵
均按权威章节的按需、非阻塞和默认静默规则处理；未被要求时不得列入日常总结、剩余问题、警告或发布
阻塞项。不得用 instrumentation 编译、单条重试、静默 skip、截图或人工判断替代自动化 verdict。
