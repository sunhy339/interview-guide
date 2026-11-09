import assert from 'node:assert/strict';
import test from 'node:test';
import { clearAgentDraft, readAgentDraft, writeAgentDraft } from './agentDraft.ts';

test('按 Agent 会话隔离保存和读取输入草稿', () => {
  const values = new Map<string, string>();
  const originalWindow = (globalThis as { window?: unknown }).window;
  Object.defineProperty(globalThis, 'window', {
    configurable: true,
    value: {
      sessionStorage: {
        getItem: (key: string) => values.get(key) ?? null,
        setItem: (key: string, value: string) => values.set(key, value),
        removeItem: (key: string) => values.delete(key),
      },
    },
  });

  try {
    writeAgentDraft('thread-a', '准备预约明天的面试');
    writeAgentDraft('thread-b', '学习 Agent Loop');
    assert.equal(readAgentDraft('thread-a'), '准备预约明天的面试');
    assert.equal(readAgentDraft('thread-b'), '学习 Agent Loop');
    clearAgentDraft('thread-a');
    assert.equal(readAgentDraft('thread-a'), '');
    assert.equal(readAgentDraft('thread-b'), '学习 Agent Loop');
  } finally {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: originalWindow,
    });
  }
});
