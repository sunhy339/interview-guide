# Agent 当前代码架构复盘

更新日期：2026-11-13

本文基于 `develop-agent` 当前代码整理，用来说明已经完成的能力、实际采用的设计方法、运行流程、产品收益和后续重构顺序。当前实现的定位是**受控的单 Agent 业务运行时**，不是自由协作的多 Agent 网络。

## 1. 当前改动按能力归类

| 类别 | 当前实现 | 主要代码位置 | 解决的问题 |
| --- | --- | --- | --- |
| 会话与上下文 | 通用会话、面试会话、会话列表、重命名、归档、删除、报告/简历/文件/知识库绑定 | `AgentController`、`AgentConversationService`、`AgentContextBindingService` | 普通对话不再自动带入旧面试报告，资料由会话明确选择 |
| 意图与能力路由 | 模型优先分类，低置信或失败时规则兜底；能力可以组合 | `AgentIntentRouter` | 普通问题、复盘、Skill、RAG、日程动作走不同能力路径，Skill 和 RAG 不互斥 |
| 上下文安全 | 固定资料顺序、历史裁剪、摘要、预算控制、资料来源标记、提示注入清理 | `AgentContextService`、`AgentPromptBudgetPolicy`、`PromptSanitizer` | 控制上下文长度，减少资料污染和提示注入影响 |
| Agent Loop | 有限计划、步骤预算、模型/工具/Token/费用/时间边界、步骤检查点和恢复 | `AgentLoopPlanner`、`AgentLoopEngine`、`AgentLoopToolExecutor` | 能执行多步任务，同时避免无限循环和无界调用 |
| 工具执行 | 工具目录、能力白名单、参数契约、结果契约、只读重试、owner 校验 | `AgentToolRegistry`、`AgentContextToolCallbackFactory` | 模型只能看到本轮允许的工具，工具结果不能直接绕过业务边界 |
| 业务动作 | 日程、简历、记忆、训练、报告导出、面试改名等先生成草稿，再确认执行 | `AgentActionService`、`AgentController` | Agent 可以做事，但不会直接把模型输出当成业务写入 |
| 长期记忆 | 结构化弱点、目标和 Skill 线索，写入需要确认，带来源和撤销/过期信息 | `AgentMemoryService`、`AgentMemorySnapshot` | 跨会话复用少量有价值的信息，避免保存整段隐私对话 |
| 长任务与恢复 | Run 状态、消息、工具事件、用量、动作状态、检查点持久化；断线后轮询恢复 | `AgentRunStateService`、`AgentRunRecoveryService`、`AgentConversationService` | 页面刷新、SSE 断开或应用重启后可以继续观察或安全恢复 |
| 追加消息 | 运行中的新消息进入 steering 队列，在工具回合后注入下一轮 | `AgentSteeringService` | 不要求用户等上一轮完全结束，也不并发创建第二个同会话任务 |
| Skill 与文档输出 | 内置简历 Skill、用户 Skill 管理、Skill 工具契约、Markdown/Word/PDF 输出入口 | `AgentUserSkillService`、`AgentUserSkillToolCallbackFactory`、`AgentDocumentController` | Agent 从“给建议”扩展为按方法生成可下载成果物 |
| 观测与评测 | Loop 轨迹、模型轮次、工具事件、Token/费用/耗时、审计、行为矩阵、真实环境验收脚本 | `AgentMetrics`、`AgentRunEventService`、`AgentAuditService`、`app/src/test` | 可以判断任务是否完成、调用了什么、消耗多少，而不只看最终文本 |
| 数据兼容与部署 | 保留已发布 Flyway 历史，新能力使用新的递增版本 | `app/src/main/resources/db/migration` | 避免线上数据库校验因迁移版本复用而启动失败 |

## 2. 实际采用的设计方法

### 2.1 模块化单体

项目采用按业务边界组织的模块化单体：

```text
Controller
  -> Application/Domain Service
    -> Repository / Provider / Infrastructure
```

Agent 通过服务调用 Interview、Resume、Knowledge Base、Schedule、Auth 和 LLM Provider 模块。当前规模下，这种方式保留了事务和数据一致性，部署也比拆成多个微服务简单。代码层面应描述为模块化单体，不应描述成已经完成的多 Agent 架构。

### 2.2 模型优先、规则兜底的路由

`AgentIntentRouter` 先让模型输出结构化的 `mode + capabilities + confidence`，再用置信度、原始用户意图和工具白名单做校验。模型调用不可用或返回非法 JSON 时，才进入本地规则兜底。

路由产物不是单一标签，而是可组合能力，例如：

```text
根据简历优化项目经历  -> CONTEXT + SKILL
结合 Skill 和知识库回答 -> SKILL + RAG
根据面试报告制定训练计划 -> INTERVIEW_REPORT + CONTEXT + ACTION
安排或取消面试 -> SCHEDULE + ACTION
```

这保留了自然语言理解能力，也给高风险动作留下了确定的安全边界。

### 2.3 Policy 和 Guardrail

工具是否可见、是否只读、是否允许重试、是否需要确认，都由路由和工具契约共同决定。模型可以提出工具调用，但以下校验仍在服务端执行：

- 当前用户是否拥有资源。
- 工具是否属于本轮能力集合。
- 参数是否符合 Schema。
- 工具结果是否符合领域结果契约。
- 写操作是否生成待确认草稿。
- 重试是否会重复副作用。

这是“模型负责提出计划，服务端负责授权和执行”的边界。

### 2.4 受控 Plan-and-Execute / ReAct

`AgentLoopPlanner` 生成有限步骤，`AgentLoopEngine` 负责执行、超时、重试、并行组和检查点，`AgentLoopToolExecutor` 负责实际观察和工具结果汇总。模型不会获得无限循环权限，也不会把隐藏推理直接展示给用户。

当前实现更接近受控的 Plan-and-Execute：只有需要工具或多步处理时才进入 Loop，普通问题可以直接生成回答。这样符合产品“输入后得到结果”的使用方式，同时保留做事能力。

### 2.5 Human-in-the-loop

创建、取消、改期、修改简历、写入记忆、创建训练任务、导出报告等写操作统一经过草稿和确认状态机。缺少日期、岗位等信息时进入 `WAITING_USER`，补充消息后复用当前 Run 继续处理。

这比让模型直接调用领域写接口更容易审计、撤销、重试和处理重复提交。

### 2.6 稳定前缀和分层上下文

上下文按稳定顺序组装：安全系统约束、任务规则、已选资料、摘要、历史消息、当前问题和运行诊断。历史消息只在预算不足时裁剪，资料按优先级压缩，当前问题和核心报告尽量保留。

上下文内容与授权意图分离：送给模型的资料先净化并标记来源；工具授权继续使用原始用户意图和服务端权限判断。资料里的“请调用某工具”不能自动升级成系统指令。

### 2.7 持久化状态机和事件记录

会话消息、Run 状态、动作草稿、Loop 检查点、工具事件、模型调用和用量分开保存。SSE 只是传输层，最终结果仍以持久化状态为准，因此断线后可以读取状态，而不是依赖浏览器内存拼接答案。

## 3. 一次 Agent 请求的代码流程

```text
前端 AgentPage
  -> AgentController
  -> AuthIdentityService 校验登录和 owner
  -> InterviewAgentService.stream
       1. 校验会话、请求幂等和当前运行状态
       2. 加载已持久化历史；通用会话不默认读取面试报告
       3. 使用原始用户消息执行 AgentIntentRouter
       4. AgentContextService 按显式绑定和路由能力组装上下文
       5. AgentPromptBudgetPolicy 估算预算并裁剪低优先级内容
       6. AgentConversationService 写入用户消息、助手占位消息和 Run
       7. AgentLoopPlanner 生成有限处理计划
       8. AgentLoopEngine 执行步骤、重试、超时和检查点
       9. AgentToolRegistry 按能力筛选工具
      10. AgentContextToolCallbackFactory 执行只读查询或动作草稿工具
      11. 需要真实写入时进入 WAITING_USER，确认后由 AgentActionService 执行
      12. 模型生成最终回答，并记录来源、grounding、模型轮次和用量
      13. SSE 返回增量事件；消息、状态、轨迹和用量最终落库
```

断线或重启时：

```text
AgentRunRecoveryService
  -> 扫描未结束 Run
  -> 校验 lease、checkpoint version 和当前状态
  -> 跳过已完成的安全步骤
  -> 对不确定的 Provider 副作用不自动重放
  -> 安全重新生成或等待用户确认
  -> 更新消息、Run 状态和审计事件
```

运行期间追加消息的路径是：

```text
新消息 -> AgentSteeringService 持久化队列
       -> 当前工具回合结束
       -> 消费同一 Run 的待处理消息
       -> 注入下一轮上下文
```

## 4. 对产品有利的设计

1. **普通聊天和面试复盘解耦。** `/agent` 默认是通用会话，只有显式进入面试模式或绑定报告才读取报告，避免旧资料污染新会话。
2. **资料引用由用户控制。** 报告、简历、文件、知识库和 Skill 都可以作为会话绑定或按意图使用，上传文件先审核再进入后续流程。
3. **Agent 可以真正完成动作。** 日程、训练、简历、记忆和文档输出不再只返回建议，而是通过工具、草稿、确认和结果契约完成闭环。
4. **失败可恢复。** 断线、刷新、超时、Provider 暂时不可用时，用户可以看到任务状态、重试或继续补充，而不是只能重新输入。
5. **操作可解释。** 用户可以查看会话名称、来源、工具动作、Loop 轨迹、Token、费用、耗时和最终结果。
6. **数据边界清楚。** owner 校验、资料审核、提示注入防护、动作确认和结果契约共同减少误操作。
7. **渐进式扩展。** 新工具可以先注册契约和只读能力，再增加动作草稿；不需要先重写整个 Agent。

## 5. AI 方面的优点

### 5.1 按需加载能力

普通概念问答不默认加载面试报告、Skill、RAG 或业务工具，减少上下文污染和无意义模型调用。识别到任务需要时，路由才打开对应能力。

### 5.2 多能力组合

路由不是“选中 Skill 就不能 RAG”的单选模式。简历优化可以同时引用简历和简历 Skill；面试复盘可以同时使用报告、训练 Skill 和知识库；日程任务可以在补充信息后继续使用日程工具。

### 5.3 有证据边界的回答

回答可以区分面试报告事实、Skill 方法、知识库资料、长期记忆和模型常识。没有报告时不会凭空生成本场表现；RAG 没有命中时也可以明确说明资料不足。

### 5.4 从文本生成扩展到成果物

内置简历优化和简历写作 Skill，加上文档输出服务，使模型结果可以进入 Word/PDF 等后续工作流，而不只停留在聊天气泡里。

### 5.5 可评测和可控成本

模型调用、Token、费用、耗时、重试、工具调用和 Loop 轨迹都有记录。行为矩阵可以检查工具选择、上下文隔离、动作状态和安全边界，质量评估不只看回答是否“像人”。

## 6. 当前可以重构和优化的地方

这些是下一阶段的工程优化，不影响当前版本作为受控单 Agent 使用。

### P0：拆分超大应用服务

当前 `InterviewAgentService`、`AgentConversationService`、`AgentActionService` 和 `AgentContextToolCallbackFactory` 承担的职责较多。建议按边界逐步拆出：

```text
AgentRunApplicationService       // 创建 Run、状态迁移、取消、恢复入口
AgentIntentApplicationService    // 分类、能力合并、工具政策
AgentContextAssemblyService      // 资料选择、前缀、预算和来源快照
AgentLoopRuntimeService          // 计划、工具回合、检查点和重试
AgentResponsePersistenceService  // 消息、SSE 结果、用量和事件落库
AgentActionApplicationService    // 草稿、确认、取消、幂等和领域结果
```

先提取接口和纯函数，再移动实现；每次只迁移一条路径，并保留旧 API，避免一次性重写导致通用会话和面试会话行为分叉。

### P1：统一会话、上下文和 Run 模型

当前通用会话和面试会话仍有两套 Controller 路径。建议在领域层统一为：

```text
AgentConversation
AgentContextBinding
AgentRun
```

面试会话只是带有默认来源的 Agent 会话。前端和后端都使用相同的会话 ID、上下文绑定 ID 和 Run ID，减少“切换页面会话消失”这类路径差异。

### P1：把运行锁和取消信号移到 Redis

当前单实例内存状态适合当前部署，但多实例时 `activeSessions`、取消信号和恢复扫描可能发生重复执行。后续应使用带 TTL 的 Redis 锁、取消事件和租约心跳，并保留数据库 Run 状态作为最终依据。

### P1：增加上下文快照指纹

在现有稳定前缀基础上增加 `contextVersion`、`contextFingerprint`、`prefixHash` 和 `messageWindowVersion`，用于判断上下文是否真正变化、诊断 Token 增长和后续缓存复用。

### P1：补齐真实 Provider 评测门禁

固定覆盖普通问答、显式引用报告、Skill、RAG、Skill+RAG、创建/取消/改期、补充信息、追加消息、断线恢复、重启恢复、提示注入和多会话切换。门禁同时检查最终结果、工具选择、动作契约、grounding、用量和数据清理。

### P2：精确恢复 Provider 内部轮次

当前恢复边界是安全重试或重新生成，尚不能消费所有 Provider 的原始模型消息和 tool-call 游标。后续需要版本化保存模型轮次、工具批次和响应 ID，并逐 Provider 明确哪些状态可安全续跑。

### P2：补 MCP Gateway

当前已有工具目录、权限、结果契约和审计基础，但还不是完整 MCP Runtime。若接入外部 MCP，应增加 Server 配置、Tool/Resource 发现、用户级 Scope、Schema 校验、超时取消、调用审计和确认策略，默认只开放只读工具。

### P2：继续拆分前端 AgentPage

`AgentPage` 目前仍聚合工作台状态和部分管理视图。可以继续拆成 `AgentConversationList`、`AgentContextBar`、`AgentMessageTimeline`、`AgentComposer`、`AgentRunStatus`、`AgentActionConfirmPanel` 和 `AgentUsageSummary`，减少切换会话、恢复任务和动作确认之间的状态耦合。

## 7. 当前合入和验收边界

- 当前交付分支：`develop-agent`。
- 最近的文档输出、Skill 管理、Flyway 版本兼容和历史迁移保留已经在该分支。
- `master` 未修改。
- `feature/agent-delivery-plan-refresh` 已经是 `develop-agent` 的祖先，不需要重复合并。
- 本地唯一未跟踪文件是 `frontend/pnpm-workspace.yaml`，本次不纳入提交。
- 线上候选环境 `18084` 已运行当前版本；部署和回归记录见既有交付文档。

当前不能宣称已经完成的事项：完整 MCP Gateway、多实例 Redis 运行锁、Provider 内部精确续跑、所有真实 Provider 多轮场景的固定门禁。这些应作为下一阶段独立功能包开发和验收。

