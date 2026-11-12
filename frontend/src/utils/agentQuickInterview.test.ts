import assert from 'node:assert/strict';
import test from 'node:test';
import {
  clearQuickInterviewHandoff,
  markQuickInterviewStarted,
  readQuickInterviewHandoff,
  writeQuickInterviewHandoff,
} from './agentQuickInterview.ts';

const handoff = {
  type: 'mockInterview' as const,
  skillId: 'java-backend',
  skillLabel: '后端开发',
  difficulty: 'mid' as const,
  questionCount: 6,
  useResume: false,
  requestId: 'request-1',
  sourceMessage: '帮我进行模拟面试，方向是后端开发',
  state: 'pending' as const,
};

function withStorage(run: (values: Map<string, string>) => void) {
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
    run(values);
  } finally {
    Object.defineProperty(globalThis, 'window', {
      configurable: true,
      value: originalWindow,
    });
  }
}

test('快速面试快捷状态按会话保存并可标记为已创建', () => {
  withStorage(() => {
    writeQuickInterviewHandoff('general:new', handoff);
    assert.equal(readQuickInterviewHandoff('general:new')?.state, 'pending');
    markQuickInterviewStarted('general:new', 'interview-1');
    const restored = readQuickInterviewHandoff('general:new');
    assert.equal(restored?.state, 'started');
    assert.equal(restored?.createdSessionId, 'interview-1');
  });
});

test('快速面试快捷状态不会串到其他 Agent 会话', () => {
  withStorage(() => {
    writeQuickInterviewHandoff('thread-a', handoff);
    assert.equal(readQuickInterviewHandoff('thread-b'), null);
    clearQuickInterviewHandoff('thread-a');
    assert.equal(readQuickInterviewHandoff('thread-a'), null);
  });
});

test('损坏的快捷状态会被丢弃', () => {
  withStorage((values) => {
    values.set('agent-quick-interview-handoff:v1:broken', '{bad-json');
    assert.equal(readQuickInterviewHandoff('broken'), null);
    assert.equal(values.has('agent-quick-interview-handoff:v1:broken'), false);
  });
});
