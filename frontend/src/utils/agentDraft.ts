const STORAGE_PREFIX = 'agent-input-draft:v1:';

function storageKey(draftKey: string): string {
  return `${STORAGE_PREFIX}${draftKey}`;
}

/** 会话草稿只保存在当前浏览器标签页，不写入服务端历史和模型上下文。 */
export function readAgentDraft(draftKey: string): string {
  if (!draftKey || typeof window === 'undefined') return '';
  try {
    return window.sessionStorage.getItem(storageKey(draftKey)) ?? '';
  } catch {
    return '';
  }
}

export function writeAgentDraft(draftKey: string, value: string): void {
  if (!draftKey || typeof window === 'undefined') return;
  try {
    const key = storageKey(draftKey);
    if (value) {
      window.sessionStorage.setItem(key, value);
    } else {
      window.sessionStorage.removeItem(key);
    }
  } catch {
    // 隐私模式或存储空间不足时，内存草稿仍然继续工作。
  }
}

export function clearAgentDraft(draftKey: string): void {
  writeAgentDraft(draftKey, '');
}
