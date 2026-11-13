import assert from 'node:assert/strict';
import test from 'node:test';

import {
  detectAgentActionIntent,
  detectDocumentExportIntent,
  detectQuickInterviewIntent,
  extractScheduleDetails,
  suggestScheduleTime,
} from './agentIntent.ts';

test('识别当前资料的 Word/PDF 输出请求，但不覆盖面试报告导出动作', () => {
  assert.equal(detectDocumentExportIntent('输出word文档'), 'docx');
  assert.equal(detectDocumentExportIntent('帮我把刚刚优化的简历导出 PDF'), 'pdf');
  assert.equal(detectDocumentExportIntent('请导出这场面试报告 PDF'), null);
  assert.equal(detectDocumentExportIntent('如何生成 Word 文档'), null);
});

const now = new Date(2026, 9, 27, 10, 15);

test('识别安排下周复习的自然语言意图并预填下周一晚上', () => {
  const intent = detectAgentActionIntent('帮我安排下周复习 Redis', now);
  assert.equal(intent?.type, 'schedule');
  assert.equal(intent?.suggestedTime, suggestScheduleTime('下周', now));
  assert.equal(intent?.suggestedTime, '2026-11-02T19:00');
});

test('识别明天下午三点的复习时间', () => {
  assert.equal(suggestScheduleTime('明天下午3点复习', now), '2026-10-28T15:00');
});

test('识别安排明天中午面试会议的自然语言意图', () => {
  const intent = detectAgentActionIntent('帮我安排一下明天12点的一个面试会议', now);
  assert.deepEqual(intent, {
    type: 'schedule',
    suggestedTime: '2026-10-28T12:00',
  });
});

test('识别面试会议的补充日期、时区、公司和职位信息', () => {
  assert.deepEqual(extractScheduleDetails('北京时间，公司是浪速公司，职位是agent开发工程师'), {
    companyName: '浪速公司',
    position: 'agent开发工程师',
    timeZone: 'Asia/Shanghai',
  });
  assert.deepEqual(detectAgentActionIntent('北京时间，公司是浪速公司，职位是agent开发工程师'), {
    type: 'schedule',
    companyName: '浪速公司',
    position: 'agent开发工程师',
    timeZone: 'Asia/Shanghai',
  });
});

test('普通复盘请求和取消安排不会误触发动作', () => {
  assert.equal(detectAgentActionIntent('请复盘一下这场面试'), null);
  assert.equal(detectAgentActionIntent('不要安排复习日程'), null);
  assert.equal(detectAgentActionIntent('帮我预约面试'), null);
  assert.equal(detectAgentActionIntent('不要保存到长期记忆'), null);
  assert.equal(detectAgentActionIntent('不要导出面试报告'), null);
});

test('识别取消面试日程意图，但不在前端直接执行', () => {
  assert.deepEqual(detectAgentActionIntent('帮我取消明天12点的面试'), { type: 'scheduleCancel' });
  assert.deepEqual(detectAgentActionIntent('把刚才安排的那个会议取消掉'), { type: 'scheduleCancel' });
});

test('识别修改或改期面试日程意图，但交给后端确认唯一目标', () => {
  assert.deepEqual(detectAgentActionIntent('帮我把明天12点的面试改到下午3点'), { type: 'scheduleUpdate' });
  assert.deepEqual(detectAgentActionIntent('把刚才的会议调整一下'), { type: 'scheduleUpdate' });
});

test('识别训练任务、记忆、标题、报告和简历动作', () => {
  assert.deepEqual(detectAgentActionIntent('帮我创建一个训练任务，重点练 Redis'), { type: 'trainingTask' });
  assert.deepEqual(detectAgentActionIntent('把这个薄弱点保存到长期记忆'), { type: 'memory' });
  assert.deepEqual(detectAgentActionIntent('给这场面试起个名字'), { type: 'title' });
  assert.deepEqual(detectAgentActionIntent('请导出这场面试报告 PDF'), { type: 'reportExport' });
  assert.deepEqual(detectAgentActionIntent('帮我优化简历'), { type: 'resumeEdit' });
});

test('识别自然语言快速发起无简历中级后端文字面试', () => {
  assert.deepEqual(detectQuickInterviewIntent(
    '帮我进行模拟面试，方向是后端开发，中等等级，不使用简历，题目给6道题',
  ), {
    type: 'mockInterview',
    skillId: 'java-backend',
    skillLabel: '后端开发',
    difficulty: 'mid',
    questionCount: 6,
    useResume: false,
  });
  assert.deepEqual(detectAgentActionIntent(
    '帮我进行模拟面试，方向是后端开发，中等等级，不使用简历，题目给6道题',
  ), {
    type: 'mockInterview',
    skillId: 'java-backend',
    skillLabel: '后端开发',
    difficulty: 'mid',
    questionCount: 6,
    useResume: false,
  });
});

test('快速面试只在明确创建命令和方向时触发', () => {
  assert.equal(detectQuickInterviewIntent('如何进行模拟面试，后端开发需要准备什么'), null);
  assert.equal(detectQuickInterviewIntent('请复盘这场后端模拟面试，题目给6道题'), null);
  assert.equal(detectQuickInterviewIntent('帮我进行模拟面试，题目给6道题'), null);
});

test('快速面试支持高级、Python和自定义题量', () => {
  assert.deepEqual(detectQuickInterviewIntent('开始一场 Python 后端高级模拟面试，使用简历，题目给10道题'), {
    type: 'mockInterview',
    skillId: 'python-backend',
    skillLabel: 'Python 后端开发',
    difficulty: 'senior',
    questionCount: 10,
    useResume: true,
  });
  assert.equal(detectQuickInterviewIntent('开始一场后端模拟面试，题目给25道题'), null);
});
