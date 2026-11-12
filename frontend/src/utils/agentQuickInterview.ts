import type { QuickInterviewIntent } from '../pages/agentIntent.ts';

const STORAGE_PREFIX = 'agent-quick-interview-handoff:v1:';
const MAX_SOURCE_MESSAGE_LENGTH = 4000;
const MAX_HANDOFF_AGE_MS = 7 * 24 * 60 * 60 * 1000;

export type QuickInterviewHandoff = QuickInterviewIntent & {
  requestId: string;
  sourceMessage: string;
  state: 'pending' | 'started';
  createdSessionId?: string;
  updatedAt: number;
};

function storageKey(scope: string) {
  return `${STORAGE_PREFIX}${encodeURIComponent(scope)}`;
}

function isQuickInterviewHandoff(value: unknown): value is QuickInterviewHandoff {
  if (!value || typeof value !== 'object') return false;
  const item = value as Partial<QuickInterviewHandoff>;
  return item.type === 'mockInterview'
    && typeof item.skillId === 'string'
    && typeof item.skillLabel === 'string'
    && (item.difficulty === 'junior' || item.difficulty === 'mid' || item.difficulty === 'senior')
    && Number.isInteger(item.questionCount)
    && (item.questionCount ?? 0) >= 3
    && (item.questionCount ?? 0) <= 20
    && typeof item.useResume === 'boolean'
    && typeof item.requestId === 'string'
    && item.requestId.length > 0
    && typeof item.sourceMessage === 'string'
    && item.sourceMessage.length > 0
    && item.sourceMessage.length <= MAX_SOURCE_MESSAGE_LENGTH
    && (item.state === 'pending' || item.state === 'started')
    && typeof item.updatedAt === 'number'
    && Number.isFinite(item.updatedAt);
}

/** 快速面试是页面快捷操作，暂存于当前标签页，不写入服务端或模型上下文。 */
export function readQuickInterviewHandoff(scope: string): QuickInterviewHandoff | null {
  if (!scope || typeof window === 'undefined') return null;
  try {
    const raw = window.sessionStorage.getItem(storageKey(scope));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!isQuickInterviewHandoff(parsed)) {
      window.sessionStorage.removeItem(storageKey(scope));
      return null;
    }
    if (Date.now() - parsed.updatedAt > MAX_HANDOFF_AGE_MS) {
      window.sessionStorage.removeItem(storageKey(scope));
      return null;
    }
    return parsed;
  } catch {
    try {
      window.sessionStorage.removeItem(storageKey(scope));
    } catch {
      // 存储不可用时不影响当前页面状态。
    }
    return null;
  }
}

export function writeQuickInterviewHandoff(
  scope: string,
  handoff: Omit<QuickInterviewHandoff, 'updatedAt'>,
): void {
  if (!scope || typeof window === 'undefined') return;
  try {
    window.sessionStorage.setItem(storageKey(scope), JSON.stringify({
      ...handoff,
      sourceMessage: handoff.sourceMessage.slice(0, MAX_SOURCE_MESSAGE_LENGTH),
      updatedAt: Date.now(),
    }));
  } catch {
    // 存储不可用时仍保留页面内状态，不阻断快速面试创建。
  }
}

export function markQuickInterviewStarted(
  scope: string,
  createdSessionId: string,
): void {
  const handoff = readQuickInterviewHandoff(scope);
  if (!handoff || !createdSessionId) return;
  writeQuickInterviewHandoff(scope, {
    ...handoff,
    state: 'started',
    createdSessionId,
  });
}

export function clearQuickInterviewHandoff(scope: string): void {
  if (!scope || typeof window === 'undefined') return;
  try {
    window.sessionStorage.removeItem(storageKey(scope));
  } catch {
    // 存储不可用时不影响当前页面状态。
  }
}
