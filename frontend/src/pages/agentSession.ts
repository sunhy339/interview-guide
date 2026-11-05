const ACTIVE_SESSION_STORAGE_KEY = 'agent-active-interview-session';
const ACTIVE_GENERAL_AGENT_THREAD_STORAGE_KEY = 'agent-active-general-thread-session';
const ACTIVE_INTERVIEW_AGENT_THREAD_STORAGE_KEY = 'agent-active-interview-thread-session';

export type AgentThreadScope = 'general' | 'interview';

function threadStorageKey(scope: AgentThreadScope) {
  return scope === 'general'
    ? ACTIVE_GENERAL_AGENT_THREAD_STORAGE_KEY
    : ACTIVE_INTERVIEW_AGENT_THREAD_STORAGE_KEY;
}

/** 页面切换时保留当前 Agent 绑定的面试报告；使用 sessionStorage 避免跨浏览器会话串用。 */
export function getStoredAgentSessionId(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage.getItem(ACTIVE_SESSION_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function rememberAgentSessionId(sessionId: string): void {
  if (!sessionId || typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(ACTIVE_SESSION_STORAGE_KEY, sessionId);
  } catch {
    // 存储被浏览器策略禁用时，当前 URL 仍然可以正常使用 Agent。
  }
}

/** 清掉已失效的报告绑定，避免 /agent 每次重新打开又恢复同一个旧链接。 */
export function clearStoredAgentSession(): void {
  if (typeof window === 'undefined') return;
  try {
    window.sessionStorage.removeItem(ACTIVE_SESSION_STORAGE_KEY);
    window.sessionStorage.removeItem(ACTIVE_GENERAL_AGENT_THREAD_STORAGE_KEY);
    window.sessionStorage.removeItem(ACTIVE_INTERVIEW_AGENT_THREAD_STORAGE_KEY);
  } catch {
    // 当前 URL 仍然可以作为最终状态来源。
  }
}

export function resolveAgentSessionId(
  routeSessionId: string | null,
  storedSessionId: string | null,
): string | null {
  return routeSessionId || storedSessionId || null;
}

/**
 * 恢复 Agent 线程时，只有保存的面试报告仍然是当前报告，才能复用保存的线程。
 *
 * 页面从 /agent 离开后再回来时，路由通常先没有参数，随后才补回 sessionId；
 * 不能因为 sessionId 已经补回而误认为用户主动切换了报告，否则会丢失当前线程。
 */
export function resolveAgentThreadId(
  routeSessionId: string | null,
  routeAgentSessionId: string | null,
  storedSessionId: string | null,
  storedAgentSessionId: string | null,
): string | null {
  if (routeAgentSessionId) return routeAgentSessionId;
  if (!storedAgentSessionId) return null;
  if (!routeSessionId || routeSessionId === storedSessionId) return storedAgentSessionId;
  return null;
}

export function getStoredAgentThreadId(scope: AgentThreadScope = 'interview'): string | null {
  if (typeof window === 'undefined') return null;
  try {
    return window.sessionStorage.getItem(threadStorageKey(scope));
  } catch {
    return null;
  }
}

export function rememberAgentThreadId(
  agentSessionId: string | null,
  scope: AgentThreadScope = 'interview',
): void {
  if (typeof window === 'undefined') return;
  try {
    const key = threadStorageKey(scope);
    if (agentSessionId) window.sessionStorage.setItem(key, agentSessionId);
    else window.sessionStorage.removeItem(key);
  } catch {
    // 当前 URL 仍然是线程状态的最终来源。
  }
}
