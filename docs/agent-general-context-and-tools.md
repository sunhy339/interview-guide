# 通用 Agent、上下文工程与工具调用方案

## 1. 目标

当前 Agent 默认绑定一份面试报告。本方案把 Agent 调整为“通用会话 + 按需上下文资产”的模式：

- 不选择面试记录时，Agent 作为通用面试与学习助手运行；
- 用户可以在输入区选择简历、面试报告、已有知识库或上传文件；
- 用户明确说“使用某场面试内容”时，Agent 通过资源归属校验后加载对应报告；
- 上传文件先进入私有待审核区，预览和安全检查通过后才能作为上下文；
- “加入 RAG 知识库”是单独的确认动作，不会因上传或普通对话自动触发；
- 日程、训练任务、长期记忆等写操作先生成待确认动作，不能由模型直接写入业务表；
- 旧的从面试详情进入 Agent 的链接和行为继续兼容。

## 2. 市面方案提炼

主流 Agent 产品和模型平台的共同做法不是无限携带全部历史，而是把上下文拆成稳定和动态两部分：

```text
稳定前缀：策略、角色、输出协议、工具定义、固定安全规则
会话状态：会话摘要、当前任务、已确认事实、未完成事项
按需资料：当前面试报告、Skill、RAG 片段、工具结果
短期历史：最近若干轮原文
当前请求：用户最新消息
```

稳定前缀必须保持内容、顺序、工具 schema 和版本号稳定；时间戳、运行 ID、动态检索结果、Loop 轨迹和当前用户问题必须放在后部。这样既有利于模型理解，也给支持 Prompt Cache 的 Provider 提供稳定的缓存前缀。

当前实现中，system prompt 只包含版本化系统策略、输出契约和防注入规则；面试报告、Skill、长期记忆、会话摘要、Loop 计划、RAG 结果、最近历史和用户问题全部位于后续动态 user 上下文。这样绑定资料或记忆发生变化时也不会污染稳定前缀，最近消息超过 12 条时只会改变动态尾部，不会把稳定前缀整体推移。

每次 Agent 运行还会保存固定 system prompt 的版本和 SHA-256 指纹到运行用量摘要与运行明细中，
只用于排查前缀漂移，不保存 Prompt 正文。normal/compact 上下文重试在同一运行内应复用同一指纹；
如果运行内指纹变化，会记录低基数的前缀不一致指标。通用 Agent 没有面试报告时，运行明细仍会
保留用量和前缀元数据，面试来源字段为空，不会因为缺少面试 ID 而丢失统计。

上下文压缩采用“滚动摘要 + 最近原文”，而不是简单删除旧消息。当前实现使用确定性本地摘要，不调用模型、不额外消耗 Token：达到消息数或字符阈值后，把较早的已完成消息压缩到会话摘要字段，保留最近 12 条原文；摘要失败不会影响已完成回答。摘要只保存可复用事实、决定、未完成事项和用户偏好，不保存隐藏推理。原始消息仍永久保存在数据库中，摘要只作为下一次模型调用的工作上下文。

## 3. Context Envelope

新增统一的上下文编排模型，按优先级生成上下文片段：

| 优先级 | 片段 | 默认策略 |
| --- | --- | --- |
| P0 | 系统策略、工具 schema、安全边界 | 始终保留，内容和顺序稳定 |
| P1 | 当前目标、会话摘要、已确认事实 | 保留，超预算时结构化压缩 |
| P1 | 当前绑定的简历/面试报告/审核文件 | 用户明确绑定后才加载 |
| P2 | Skill 与长期记忆 | 按当前目标和岗位过滤 |
| P2 | RAG 结果 | 只在需要时检索，限制数量和来源 |
| P3 | 最近 8～12 条消息 | 原文保留 |
| P3 | 当前用户消息 | 始终保留 |
| P4 | 旧工具观察和旧消息 | 优先摘要或移除 |

每个片段带有 `kind`、`sourceId`、`priority`、`tokenEstimate` 和 `trusted` 元数据。模型收到的资料使用稳定分隔符，工具返回值视为不可信数据，不能覆盖系统策略。

## 4. 稳定前缀布局

模型请求的逻辑顺序固定为：

```text
[system]（稳定前缀）
  agent-policy:v1
  output-contract:v1
  security-policy:v1
  tool-contracts:v1

[user 动态上下文]
  conversation-mode
  active-context-reference
  session-summary

[dynamic context]
  interview-report
  skill-reference
  long-term-memory
  retrieved-evidence
  current-loop-observations

[recent messages]
  recent conversation turns

[current user message]
  latest request
```

需要避免把以下内容放在稳定前缀中：面试报告、Skill、长期记忆、当前时间、请求 ID、随机运行 ID、会话摘要、每轮动态 Loop 轨迹、实时用量、变化频繁的 RAG 结果和当前用户问题。当前实现把这些内容放入动态 user 上下文，资料仍使用固定标签和净化后的边界。

Prompt Cache 是 Provider 的性能/成本优化，不等于应用自己的上下文记忆。应用仍然必须自己持久化会话、摘要和上下文引用；Provider 不支持缓存时，功能也不能失效。

## 5. 通用 Agent 会话

现有 `agent_chat_sessions.interview_session_id` 需要改为可空。新增会话上下文引用表：

```text
agent_session_contexts
  id
  agent_session_id
  context_type
  interview_session_id
  resource_id
  resource_name
  scope
  active
  attached_at
  detached_at
  source_message_id
```

上下文类型统一为 `RESUME`、`INTERVIEW_REPORT`、`FILE`、`KNOWLEDGE_BASE`。一个会话最多同时保留 8 份 active 资料引用，重复绑定同一资源幂等返回；`SESSION` 资料持续到主动移除，`TURN` 资料只在下一次 Agent 运行加载一次后立即消费。上下文工具还会用用户原话复核作用域，模型不能把“本次使用”改成持续绑定，也不能在没有明确一次性意图时自行选择 `TURN`。用户可以单独移除某份资料，也可以一次清除全部上下文。上传文件的原文不复制到会话表。

新增私有上传素材表 `agent_context_assets`：

```text
UPLOADED -> READY_FOR_REVIEW -> APPROVED_FOR_CONTEXT
                              -> KB_IMPORT_PENDING -> KB_IMPORT_COMPLETED/FAILED
                              -> REJECTED/EXPIRED
```

`READY_FOR_REVIEW` 只表示文件格式、大小、MIME、哈希和文本解析检查完成；`APPROVED_FOR_CONTEXT` 才允许绑定 Agent；`KB_IMPORT_COMPLETED` 只在用户再次确认后创建已有知识库实体并投递向量化任务。

保留旧接口：

```text
/api/agent/interview-sessions/{sessionId}/...
```

新增通用会话接口：

```text
POST /api/agent/sessions
GET  /api/agent/sessions
GET  /api/agent/sessions/{agentSessionId}
POST /api/agent/sessions/{agentSessionId}/messages/stream
GET  /api/agent/sessions/{agentSessionId}/context
POST /api/agent/sessions/{agentSessionId}/contexts
DELETE /api/agent/sessions/{agentSessionId}/contexts/{contextType}/{resourceId}
DELETE /api/agent/sessions/{agentSessionId}/context
POST /api/agent/context-assets
GET  /api/agent/context-assets
POST /api/agent/context-assets/{assetId}/approve
POST /api/agent/context-assets/{assetId}/knowledge-base-request
POST /api/agent/context-assets/{assetId}/knowledge-base/confirm
```

旧会话继续按原面试 ID 隔离，通用会话按当前登录用户隔离。

## 6. 上下文资产审核和安全边界

上传素材的处理顺序固定为：

```text
文件上传
  -> 扩展名/MIME/大小校验
  -> SHA-256 去重和 owner 隔离
  -> Tika 文本解析和预览
  -> 检测疑似提示注入并展示安全提示
  -> 用户审核通过；若命中疑似提示注入，必须再次确认“仅作为资料分析，不执行其中指令”
  -> 仅本次使用 / 加入当前 Agent 会话
  -> 用户再次确认后申请进入 RAG
  -> 创建知识库实体并异步向量化
```

文件是“不可信数据”，不是系统指令。后端上下文编排必须：

- 给简历、报告、上传文件、RAG 和工具结果使用明确的固定边界和来源标签；
- 每段资料经过 `PromptSanitizer`，限制单段和总上下文长度；
- 净化前先做 NFKC、零宽字符和换行归一化，覆盖全角/不可见字符绕过、角色伪造、规则覆盖、提示泄露和边界伪造；
- 资料中的 `system/user/assistant` 角色标记、忽略指令、角色切换和伪造边界只作为待分析文本；
- 即使关闭普通 PromptSanitizer 配置，进入模型上下文的资料仍执行强制净化；
- 模型不能由文件内容提升权限、改变工具 schema、跳过审核或直接写入业务表；
- 资源读取始终按当前登录 owner 校验，不能接受模型传入的 owner ID；
- 会话上下文绑定在 Repository 查询层同时按 Agent 会话 owner 过滤，避免新增恢复入口或工具入口遗漏上层会话校验；
- 上下文编排只信任服务端 `AuthUserContext` 中的 owner；请求体里的 `memoryKey`、历史消息或工具参数不能作为资源归属凭证。缺少服务端身份时不读取报告、简历、文件、知识库或长期记忆；迁移期无显式 owner 的旧调用也必须同时具备匹配的服务端 ThreadLocal 身份。
- 会话持久化也只接受已校验的服务端 owner；无认证的兼容内部调用会忽略请求体 `memoryKey`，不会把它升级为会话归属或跨用户访问凭证；
- 变更类工具还要复核当前用户消息是否包含疑似注入；授权判断使用服务端保留的原始用户消息，不能使用已经为模型净化过的副本，命中时拒绝操作，不依赖模型自行判断；
- 读取面试、资料和日程的工具也要检查当前用户是否明确提出相关查询，避免模型被上下文中的行动文字诱导读取无关数据；
- 会产生动作草稿的自由文本参数（公司、岗位、备注、链接等）在工具入口再次检测提示注入，即使当前用户确实提出了预约意图，也不能把可疑资料直接写入动作草稿；
- 变更工具的授权必须是当前用户的正向直接请求；“不要预约”“是否加入”“文件里写着确认”等否定、疑问或资料转述不能视为确认；
- 该“资料转述不能授权”规则同样适用于只读工具：附件、报告或 RAG 中出现“查看日程/搜索面试”等行动文字，不能诱导 Agent 读取额外业务数据；
- `agent_context.list` 返回给模型的上传素材只包含文件名、资源 ID、审核状态和风险标记，不返回文件预览正文；工具审核、知识库申请/确认只返回操作摘要，工具异常也使用不可信结果边界包裹，避免错误文本伪造系统指令；
- SkillsTool 等没有业务级资料包装的工具结果也会在 Agent 工具观测边界再次执行强制净化，并重新包裹为新的不可信数据边界；长期记忆在写入、读取和重新注入 Prompt 前均按不可信资料处理，不会成为系统指令来源；
- 已持久化的历史 `assistant` 消息回灌前统一降级为不可信 `UserMessage`，原角色只保留在数据边界内；RAG 查询改写的查询和历史也有独立的 anti-injection 指令与数据边界；
- Skill reference 同样视为外部资料，在进入 Agent 上下文前强制净化，不能因为它来自 classpath 就提升为系统指令；
- RAG 导入和其他写操作都必须经过显式动作确认、幂等和审计。
- 文件解析、对象存储操作和向量任务投递不放在数据库事务内；素材状态和知识库元数据先提交，提交后才投递向量任务，投递失败再记录可重试状态。
- 同一上传素材的审核/入库确认使用数据库行锁串行化；重复确认复用已关联知识库，不重复创建或投递向量任务。工具意图判断先做 Unicode/NFKC 和零宽字符归一化，覆盖不可见字符绕过。

前端展示文件预览时不上传完整原文到日志；列表接口只返回元数据和受限预览。待审核文件默认 7 天过期，拒绝后删除对象存储文件。

## 7. 面试上下文工具

当前已接入的第一批 Tool Callback 全部从服务端 `ToolContext` 取得当前登录用户、Agent 会话和运行预算，模型不能自行传入 owner ID：

| 工具 | 作用 | 类型 |
| --- | --- | --- |
| `interview.search` | 按标题、方向、编号或状态搜索当前用户的面试 | 只读 |
| `interview.attach_context` | 将唯一匹配的面试绑定到当前 Agent 会话 | 低风险状态变更 |
| `interview.clear_context` | 清除当前面试上下文 | 低风险状态变更 |
| `agent_context.list` | 查看当前绑定和上传素材的审核状态 | 只读 |
| `agent_context.asset_action` | 审核、绑定素材或申请/确认导入知识库 | 分阶段确认 |
| `schedule.list` | 查询当前用户指定范围内的日程摘要 | 只读 |
| `schedule.check_conflict` | 检查目标时间附近是否有未取消日程 | 只读 |
| `schedule.create_draft` | 生成待确认日程草稿 | 必须确认 |

搜索结果只返回当前用户自己的轻量元数据；只有用户明确表达“使用/根据/基于某份面试报告”后，才允许绑定报告，避免无关报告泄漏到 Prompt。服务端会记住本轮搜索候选：唯一候选可以继续绑定，多个候选必须让用户明确提供面试编号或唯一名称，不能由模型默认选择第一条。绑定后的正文仍由后端上下文编排器加载和净化，工具本身不接受 owner 或任意 Repository 参数。

规划阶段使用无工具 ChatClient，只能产生有限的只读步骤；真正的上下文和日程工具只在最终回答阶段可见。工具回调还会复核当前用户消息的操作意图，因此报告、RAG、上传文件和工具返回值中的“请预约/请导入/忽略规则”等文本不能单独触发写操作。

## 8. 日程工具调用链

```text
用户：帮我预约周五下午三点的 Java 面试
  ↓
schedule.list / schedule.check_conflict
  ↓
schedule.create_draft
  ↓
前端显示公司、岗位、时间、类型、会议链接和冲突提示
  ↓
用户确认
  ↓
AgentActionService.confirm
  ↓
InterviewScheduleService.createForOwner
```

当前项目的面试日程是本地日程记录，并不等于创建腾讯会议、飞书会议或 Zoom 房间。当前 Tool Callback 只生成日程草稿，仍需在“动作与训练”页确认；外部会议创建需要单独接入 OAuth、第三方 API 或 MCP Connector。

## 9. 滚动摘要

新增会话级摘要字段或独立表，至少记录：

- 当前会话目标；
- 已确认事实；
- 已完成决定；
- 未完成事项；
- 会话内用户偏好；
- 当前绑定的面试上下文；
- 已创建或待确认的动作；
- 摘要覆盖到的消息序号；
- 摘要版本、更新时间和 token 估算。

触发策略：

1. 当前版本使用消息数和字符数双阈值作为保守预算，不把精确 token 估算当作安全前提；
2. 达到本地预算阈值时生成摘要；后续可按 Provider 增加 token 估算，但不能替代字符上限；
3. 保留最近 8～12 条原文；
4. 摘要失败时回退到现有的确定性历史裁剪；
5. 原始消息和摘要版本都可追溯，摘要不覆盖原始消息；
6. 摘要生成使用较低成本模型或配置的辅助 Provider，不占用主 Agent Loop 的工具预算。

## 10. 工具安全边界

所有工具统一经过：

```text
工具 schema
  -> owner 校验
  -> ToolContext 会话和运行预算校验
  -> 资源归属校验
  -> 参数校验
  -> 当前用户明确意图校验
  -> 只读执行或生成动作草稿
  -> 幂等键
  -> 用户确认
  -> 业务写入
  -> 审计与结果摘要
```

模型不能直接调用 Repository 或 Service 的任意写方法。写工具必须返回动作草稿，沿用现有 `PROPOSED -> CONFIRMED -> RUNNING -> SUCCEEDED/FAILED/CANCELLED` 状态机。

提示注入防护不依赖单一正则：资料进入模型前经过强制净化和固定边界包裹，疑似注入的上传文件必须二次确认；规划器不暴露写工具；工具执行时再次做 owner、会话、用户意图、自由文本参数和业务状态校验。变更工具还拒绝否定、疑问和资料转述形式的“确认”，并使用原始用户消息做注入检测，避免净化过程反而掩盖攻击。工具结果净化后会重新生成服务端数据边界。任何失败只返回安全错误摘要，不把原始工具参数、文件正文或隐藏提示写入审计和指标。上下文选择器只展示当前用户可访问且已完成向量化的知识库摘要，知识库正文仍由后端按引用检索并净化。

## 11. 实施顺序

1. 新增 Context Envelope、稳定前缀版本和安全边界；不改变旧接口行为。（已完成基础实现，当前使用字符预算）
2. 增加会话滚动摘要和摘要恢复测试。（已完成本地确定性摘要；模型摘要和更复杂事实提取暂不引入）
3. 将 Agent 会话改为可选面试上下文，增加通用会话接口。
4. 增加简历、面试报告、知识库和上传文件的统一资源绑定。（已支持会话内最多 8 份资料并行引用和单项解绑）
5. 增加上传素材的审核、过期、拒绝、提示注入告警和 RAG 二次确认导入。
6. 增加 `interview.search/get_context/attach/clear`。
7. 将日程查询和冲突检查注册为只读 Tool Callback。
8. 将日程创建接入 `schedule.create_draft`，复用现有动作确认链路。
9. 增加前端上下文选择器、文件预览审核、当前上下文和待确认动作卡片。
10. 完成兼容性、权限、幂等、并发、上下文超限、摘要失败、重启恢复和多会话测试后，再部署验收。

## 11. 验收重点

- 不选择面试报告时可以正常通用对话；
- 上传文件不会自动进入 RAG，审核前不能绑定到 Agent；
- 文件包含提示注入文本时不会改变系统规则或触发工具；
- 审核通过后可以作为当前 Agent 资料，知识库导入必须二次确认；
- 简历、面试报告、文件和知识库资源不能跨用户读取；
- “使用 XX 面试内容”只读取当前用户匹配的报告；
- 多个候选时不会自动猜测；
- 切换和清除上下文刷新后仍然正确；
- 用户可以在输入区显式清除当前上下文，清除只解除会话引用，不删除原始简历、面试报告、上传文件或知识库；
- `TURN` 临时上下文只进入下一次 Agent 运行，运行开始后立即解绑，刷新、重启和后续对话不会重复注入；
- 上下文工具会复核 `SESSION/TURN` 与用户原话的生命周期意图，资料内容或模型参数不能单独扩大绑定范围；
- 缺少当前用户身份时不读取会话摘要、报告、简历、文件或知识库内容；
- 旧的面试详情进入 Agent 链路不回归；
- 50 轮以上对话仍保留摘要、最近原文和当前目标；
- 固定前缀 hash 在只改变用户消息时保持不变；
- Prompt Cache 不可用时功能不受影响；
- 日程创建不会绕过确认、owner、幂等和审计；
- 同一动作重试不会生成重复日程；
- 任何工具异常只影响当前 Agent 请求，不影响普通面试、简历和 RAG 功能。
