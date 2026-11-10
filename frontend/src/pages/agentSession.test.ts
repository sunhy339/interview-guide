import assert from 'node:assert/strict';
import test from 'node:test';

import {
  clearStoredAgentSession,
  getStoredInterviewAgentThreadId,
  getStoredAgentThreadId,
  isExplicitInterviewAgentMode,
  rememberInterviewAgentThreadId,
  rememberAgentThreadId,
  resolveAgentSessionId,
  resolveAgentThreadId,
} from './agentSession.ts';

test('URL 中的面试会话优先于页面保存的会话', () => {
  assert.equal(resolveAgentSessionId('from-url', 'from-storage'), 'from-url');
});

test('从导航返回 Agent 页面时恢复最近一次面试会话', () => {
  assert.equal(resolveAgentSessionId(null, 'last-session'), 'last-session');
});

test('没有 URL 和保存值时保持空白上下文', () => {
  assert.equal(resolveAgentSessionId(null, null), null);
});

test('只有显式面试模式才允许读取面试报告参数', () => {
  assert.equal(isExplicitInterviewAgentMode('interview'), true);
  assert.equal(isExplicitInterviewAgentMode('INTERVIEW'), true);
  assert.equal(isExplicitInterviewAgentMode('general'), false);
  assert.equal(isExplicitInterviewAgentMode(null), false);
});

test('补回保存的面试报告参数时继续恢复同一 Agent 线程', () => {
  assert.equal(
    resolveAgentThreadId('last-session', null, 'last-session', 'thread-b'),
    'thread-b',
  );
});

test('明确打开另一份面试报告时不串用上一份 Agent 线程', () => {
  assert.equal(
    resolveAgentThreadId('another-session', null, 'last-session', 'thread-b'),
    null,
  );
});

test('URL 中明确指定的 Agent 线程优先', () => {
  assert.equal(
    resolveAgentThreadId('last-session', 'thread-url', 'last-session', 'thread-storage'),
    'thread-url',
  );
});

test('通用 Agent 和面试报告线程使用独立的恢复位置', () => {
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
    rememberAgentThreadId('general-thread', 'general');
    rememberAgentThreadId('interview-thread', 'interview');

    assert.equal(getStoredAgentThreadId('general'), 'general-thread');
    assert.equal(getStoredAgentThreadId('interview'), 'interview-thread');

    clearStoredAgentSession();
    assert.equal(getStoredAgentThreadId('general'), null);
    assert.equal(getStoredAgentThreadId('interview'), null);
  } finally {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: originalWindow,
    });
  }
});

test('不同面试报告分别恢复各自最近使用的 Agent 线程', () => {
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
    rememberInterviewAgentThreadId('report-a', 'thread-a');
    rememberInterviewAgentThreadId('report-b', 'thread-b');

    assert.equal(getStoredInterviewAgentThreadId('report-a'), 'thread-a');
    assert.equal(getStoredInterviewAgentThreadId('report-b'), 'thread-b');

    rememberInterviewAgentThreadId('report-a', null);
    assert.equal(getStoredInterviewAgentThreadId('report-a'), null);
    assert.equal(getStoredInterviewAgentThreadId('report-b'), 'thread-b');
  } finally {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: originalWindow,
    });
  }
});
