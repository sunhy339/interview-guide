import type { AgentConversationResponse, AgentGoal } from '../types/agent';

export interface AgentRunRequest {
  goal: AgentGoal;
  message: string;
  providerId?: string;
  agentSessionId?: string;
}

export interface AgentRunMessageIds {
  userMessageId: number;
  assistantMessageId: number;
}

export interface AgentRunMessage {
  id: number;
  role: 'assistant' | 'user';
  content: string;
}

/** 合并恢复快照和当前流中的临时消息，避免旧快照覆盖刚显示的回答。 */
export function mergeTransientAgentMessages(
  persisted: AgentRunMessage[],
  transient: AgentRunMessage[],
): AgentRunMessage[] {
  const merged = [...persisted];
  const transientUser = transient.find((message) => message.role === 'user' && message.content.trim());
  if (transientUser) {
    const userIndex = merged.map((message) => message.role === 'user' && message.content === transientUser.content)
      .lastIndexOf(true);
    if (userIndex < 0) merged.push(transientUser);

    const resolvedUserIndex = merged.map((message) => message.role === 'user' && message.content === transientUser.content)
      .lastIndexOf(true);
    const assistantAfterUser = resolvedUserIndex >= 0
      && merged.slice(resolvedUserIndex + 1).some((message) => message.role === 'assistant' && message.content.trim());
    const transientAssistant = transient.find((message) => message.role === 'assistant' && message.content.trim());
    if (transientAssistant && !assistantAfterUser) merged.push(transientAssistant);
    return merged;
  }

  for (const message of transient) {
    if (!message.content.trim()) continue;
    if (!merged.some((existing) => existing.role === message.role && existing.content === message.content)) {
      merged.push(message);
    }
  }
  return merged;
}

/** 重试时优先使用用户当前选择的模型；没有选择时才沿用失败任务的模型。 */
export function resolveRetryProviderId(currentProviderId: string, previousProviderId?: string) {
  const current = currentProviderId.trim();
  return current || previousProviderId;
}

function isAgentGoal(value: string | null | undefined): value is AgentGoal {
  return value === 'general' || value === 'review' || value === 'practice' || value === 'plan';
}

/** 从持久化失败会话中恢复可重试的原始用户请求。 */
export function getRetryableRequest(conversation: AgentConversationResponse): AgentRunRequest | null {
  if (conversation.runStatus !== 'FAILED') return null;
  const lastUserMessage = [...conversation.messages].reverse()
    .find((message) => message.role === 'user' && message.content.trim().length > 0);
  if (!lastUserMessage) return null;

  const request: AgentRunRequest = {
    goal: conversation.interviewSessionId === null
      ? 'general'
      : isAgentGoal(conversation.goal) ? conversation.goal : 'review',
    message: lastUserMessage.content,
    providerId: conversation.providerId || undefined,
  };
  return conversation.agentSessionId
    ? { ...request, agentSessionId: conversation.agentSessionId }
    : request;
}

/**
 * 重试同一请求前移除上一次失败的临时消息，避免 UI 重复展示，
 * 也避免把错误提示作为 assistant 上下文再次发送给模型。
 */
export function removeLastRunMessages(
  messages: AgentRunMessage[],
  request: AgentRunRequest,
  ids?: AgentRunMessageIds | null,
): AgentRunMessage[] {
  let remaining = ids
    ? messages.filter((message) => message.id !== ids.userMessageId && message.id !== ids.assistantMessageId)
    : [...messages];

  // 页面恢复会重新生成展示消息 ID；即使持久化前的临时 ID 已失效，
  // 重试仍应删除末尾那一对失败请求，避免把错误提示再次留在聊天历史中。
  const lastIndex = remaining.length - 1;
  const previousIndex = lastIndex - 1;
  if (
    lastIndex >= 1
    && remaining[lastIndex].role === 'assistant'
    && remaining[previousIndex].role === 'user'
    && remaining[previousIndex].content === request.message
  ) {
    remaining = remaining.slice(0, previousIndex);
  } else if (
    lastIndex >= 0
    && remaining[lastIndex].role === 'user'
    && remaining[lastIndex].content === request.message
  ) {
    remaining = remaining.slice(0, lastIndex);
  }

  return remaining;
}
