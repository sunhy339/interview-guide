# Agent 质量评测

当前评测器版本为 `agent-quality-v2`，除回答、引用和 Loop 外，还校验真实运行状态与待处理交互。

## 离线门禁

普通测试和 `agentEvaluation` 会校验 `dataset-v1.jsonl`、引用边界、Markdown 基本格式和
Loop 预算，不调用模型：

```powershell
.\gradlew.bat :app:agentEvaluation --no-daemon
```

路由和能力裁剪的确定性行为矩阵位于 `behavior-matrix-v1.jsonl`，覆盖普通问答、面试
复盘、简历/文件上下文、Skill+RAG 组合以及日程创建/取消/改期。它不调用模型，直接
验证“普通问题不加载业务工具”和“日程动作只暴露对应工具”等安全边界；运行完整测试
时会自动执行：

```powershell
.\gradlew.bat :app:test --tests interview.guide.modules.agent.evaluation.AgentBehaviorMatrixTest
```

## 真实运行结果

将每次 Agent 运行整理为 JSONL，每行至少包含以下字段：

```json
{"sampleId":"review-completeness-001","provider":"pixel","model":"gpt-5.6-luna","promptVersion":"agent-coach-v1","codeVersion":"git-sha","answer":"## 结论\n...","promptTokens":120,"completionTokens":80,"cost":0.00048,"runStatus":"COMPLETED","runError":"","pendingType":"","modelCalls":1,"durationMillis":2100,"sources":[],"loopIterations":[]}
```

`sampleId` 必须存在于 `dataset-v1.jsonl`；`provider`、`model`、`promptVersion` 和
`codeVersion` 用于比较不同运行；`answer` 会参与评分，但不会写入最终报告；`sources`
和 `loopIterations` 用于检查 RAG 引用和 Loop 边界。`runStatus`、`runError`、`pendingType`、
`modelCalls` 和 `durationMillis` 用于检查任务是否真正完成；默认样本期望 `COMPLETED`，
如需评测补充信息或审批场景，可在样本中声明 `expectedRunStatus`。

不再需要手工整理真实运行结果时，可使用采集脚本自动登录、创建临时面试、逐条运行语料，
并从持久化会话读取最终回答和用量：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-quality-collector.ps1 `
  -BaseUrl http://localhost:18083 `
  -CreateInterview `
  -ProviderId pixel `
  -OutputPath build/agent-eval/real-run.jsonl
```

脚本默认使用 `pixel` 提供方并执行完整语料；可传入 `-ProviderId` 切换到候选环境中已启用的
提供方，也可传入 `-MaxSamples 1` 做低成本烟测。`-CreateInterview` 创建的是随机题目，
只适合验证接口、模型、SSE、持久化和用量链路，不保证满足语料中的具体事实或 RAG 条件。
它只归档自己创建的 Agent 线程，并删除自己创建的临时面试；真实模型调用会产生费用，应优先在候选环境执行。采集器
发现 SSE 业务错误、请求未进入 `RUNNING` 或最终状态不是 `COMPLETED` 时会立即失败，不会把
空回答写成成功样本。

要运行质量门禁，必须传入已经准备好的确定性 `-InterviewSessionId`，并确保该面试报告和
知识库配置与 `dataset-v1.jsonl` 的样本契约匹配。脚本会拒绝组合使用
`-RunQualityGate -CreateInterview`，避免把随机面试误报成质量回归。

## 多轮交互门禁

`interaction-matrix-v1.jsonl` 专门验证真实 Agent 的状态机，而不是只验证最终文本：

- 普通问题的连续两轮对话仍然是 `COMPLETED`；
- 缺少日程字段时进入 `WAITING_USER/INPUT`，补充后进入
  `WAITING_USER/APPROVAL`；
- 确认动作后原会话恢复并最终进入 `COMPLETED`；
- 取消动作后原会话恢复并最终进入 `COMPLETED`，且动作状态为 `CANCELLED`。
- 日程先创建并确认，再发起改期；评测检查改期工具、独立新 Run、审批续跑以及 `SCHEDULE_UPDATED` 结果契约。
- 通用 Agent 在没有显式资料时不读取面试报告；只有先绑定面试报告后，运行来源才出现 `interview`。
- 缺信息续跑、确认和取消必须复用同一个 `runId`；普通新消息必须创建新的运行代次。
- 确认/取消不能新增一条用户消息，避免把系统动作结果伪装成用户输入或造成上下文重复。
- 日程动作步骤还会校验 Trace 中出现了正确的工具资源，例如创建必须命中
  `schedule_create_draft`；这只验证工具选择，不把工具结果文本当作成功依据。
- 真实 Run 事件中的模型终态也必须匹配事件名：完成对应 `SUCCEEDED`，失败对应 `FAILED`，取消对应 `CANCELLED`。

其中 `general-without-explicit-context` 和 `general-with-explicit-interview-context`
使用真正的通用会话接口，分别验证“无绑定不读取”和“显式绑定后读取”。

先采集真实运行结果，再执行独立门禁：

```powershell
pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent-interaction-collector.ps1 `
  -BaseUrl http://localhost:18083 `
  -InterviewSessionId <已准备好的面试会话 ID> `
  -ProviderId pixel `
  -OutputPath build/agent-eval/interaction-run.jsonl `
  -RunQualityGate
```

也可以使用 `-CreateInterview` 做接口和状态机 smoke，但随机面试不代表报告复盘质量。
脚本会验证每一步的状态、pending 类型和动作状态，并要求本次结果覆盖矩阵中的全部场景，失败时不会生成“通过”的结果。

执行真实评测并生成 `JSON + Markdown` 报告：

```powershell
.\gradlew.bat :app:agentEvaluation `
  "-PagentEvalInput=build/agent-eval/run.jsonl" `
  "-PagentEvalReportDir=build/reports/agent-eval" `
  "-PagentEvalRunId=real-pixel-gpt5-luna-001" `
  --no-daemon
```

报告只保留样本 ID、模型/版本、Token、费用、分数和违规项，不把完整回答写入报告。
