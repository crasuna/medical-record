# 问题跟踪器：GitHub

本仓库的问题、规格和实现任务均记录在 GitHub Issues 中。所有相关操作使用 `gh` CLI 完成。

## 约定

- **创建问题**：`gh issue create --title "..." --body "..."`
  - 正文包含多行内容时，使用 `--body-file` 或当前 shell 支持的多行输入方式，避免复杂转义。
- **读取问题**：`gh issue view <number> --comments`
  - 同时获取问题正文、标签和评论；需要结构化处理时使用 `--json` 与 `--jq`。
- **列出问题**：

  `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'`

  根据任务需要增加适当的 `--label` 和 `--state` 过滤条件。
- **评论问题**：`gh issue comment <number> --body "..."`
- **添加标签**：`gh issue edit <number> --add-label "..."`
- **移除标签**：`gh issue edit <number> --remove-label "..."`
- **关闭问题**：`gh issue close <number> --comment "..."`

在仓库克隆目录内运行时，由 `git remote -v` 推断目标仓库；`gh` 通常会自动完成该解析。

## 将 Pull Request 作为分诊入口

**PRs as a request surface: no.**

当此值设为 `yes` 时，外部 Pull Request 将使用与 Issue 相同的标签和状态进入分诊流程，并使用对应的 `gh pr` 命令：

- **读取 PR**：`gh pr view <number> --comments`
- **查看差异**：`gh pr diff <number>`
- **列出待分诊的外部 PR**：

  `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`

  仅保留 `authorAssociation` 为 `CONTRIBUTOR`、`FIRST_TIME_CONTRIBUTOR` 或 `NONE` 的 PR；忽略 `OWNER`、`MEMBER` 和 `COLLABORATOR`。
- **评论、标记和关闭**：使用 `gh pr comment`、`gh pr edit --add-label`、`gh pr edit --remove-label` 和 `gh pr close`。

GitHub 的 Issue 和 Pull Request 共用同一个编号空间，因此单独出现的 `#42` 可能代表任意一种对象。先运行 `gh pr view 42`，若不存在，再运行 `gh issue view 42`。

## 当技能要求“发布到问题跟踪器”

创建一个 GitHub Issue。

## 当技能要求“获取相关任务”

运行：

`gh issue view <number> --comments`

## Wayfinding 操作

`wayfinder` 使用一个主 Issue 作为决策地图，并使用子 Issue 表示各个决策任务。

- **决策地图**：
  - 使用一个带有 `wayfinder:map` 标签的 Issue。
  - Issue 正文保存 Notes、Decisions-so-far 和 Fog。
  - 创建命令：`gh issue create --label wayfinder:map`。
- **子任务**：
  - 使用 GitHub Sub-issues 将子 Issue 关联到决策地图。
  - 如果仓库未启用 Sub-issues，则在地图正文中添加任务列表，并在子 Issue 正文顶部写入 `Part of #<map>`。
  - 标签采用 `wayfinder:<type>`，其中类型为 `research`、`prototype`、`grilling` 或 `task`。
  - 子任务被领取后，将其指派给当前负责开发的人员。
- **阻塞关系**：
  - 优先使用 GitHub 原生 Issue Dependencies。
  - 添加阻塞关系时运行：

    `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`

  - `<blocker-db-id>` 必须是阻塞 Issue 的数字数据库 ID，可通过以下命令获得：

    `gh api repos/<owner>/<repo>/issues/<number> --jq .id`

  - 不要使用 Issue 编号或 `node_id` 代替数据库 ID。
  - GitHub 的 `issue_dependencies_summary.blocked_by` 表示当前仍未关闭的阻塞项。
  - 如果原生依赖关系不可用，则在子 Issue 正文顶部使用：

    `Blocked by: #<number>, #<number>`

  - 只有所有阻塞 Issue 均已关闭时，当前任务才视为解除阻塞。
- **查询可执行任务**：
  - 列出地图下所有未关闭的子 Issue。
  - 排除仍有开放阻塞项的任务。
  - 排除已经有负责人认领的任务。
  - 按地图中的顺序选取第一个可执行任务。
- **领取任务**：

  `gh issue edit <number> --add-assignee @me`

  领取动作是处理该任务时的第一次外部写入。
- **完成任务**：
  - 使用 `gh issue comment` 写入结论。
  - 使用 `gh issue close` 关闭任务。
  - 将结论摘要和链接追加到主地图的 Decisions-so-far 部分。
