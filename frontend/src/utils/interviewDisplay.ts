export interface InterviewDisplayRecord {
  sessionId: string;
  title?: string | null;
  skillId?: string | null;
  status?: string | null;
  evaluateStatus?: string | null;
  overallScore?: number | null;
  createdAt?: string | null;
}

const skillLabels: Record<string, string> = {
  'java-backend': 'Java 后端',
  'java': 'Java',
  'frontend': '前端',
  '前端': '前端',
  'python': 'Python',
  'go': 'Go',
  'agent': 'Agent 开发',
  'agent-development': 'Agent 开发',
};

const statusLabels: Record<string, string> = {
  CREATED: '未开始',
  IN_PROGRESS: '进行中',
  PAUSED: '已暂停',
  COMPLETED: '已完成',
  FAILED: '已失败',
  PENDING: '评估中',
  PROCESSING: '评估中',
};

function isGeneratedSessionTitle(title: string, sessionId: string) {
  const normalizedTitle = title.replace(/^#/, '').trim().toLowerCase();
  const normalizedSessionId = sessionId.trim().toLowerCase();
  return normalizedTitle.length === 8
    && /^[a-z0-9]+$/.test(normalizedTitle)
    && normalizedSessionId.endsWith(normalizedTitle);
}

export function formatSkillLabel(skillId?: string | null) {
  const normalized = skillId?.trim();
  if (!normalized) return '模拟面试';
  return skillLabels[normalized.toLowerCase()]
    || normalized.replace(/[-_]+/g, ' ').replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function formatInterviewTitle(record: InterviewDisplayRecord) {
  const title = record.title?.trim();
  if (title && !isGeneratedSessionTitle(title, record.sessionId)) return title;
  return `${formatSkillLabel(record.skillId)}面试`;
}

export function formatInterviewStatus(record: InterviewDisplayRecord) {
  if (record.evaluateStatus && statusLabels[record.evaluateStatus]) {
    return statusLabels[record.evaluateStatus];
  }
  if (record.status && statusLabels[record.status]) return statusLabels[record.status];
  if (record.overallScore != null) return `评分 ${record.overallScore}`;
  return '待复盘';
}

export function formatInterviewDate(value?: string | null) {
  if (!value) return '时间未知';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '时间未知';
  return date.toLocaleString('zh-CN', {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

export function formatInterviewMeta(record: InterviewDisplayRecord, includeId = false) {
  const parts = [
    formatSkillLabel(record.skillId),
    formatInterviewDate(record.createdAt),
    formatInterviewStatus(record),
  ];
  if (includeId) parts.push(`编号 #${record.sessionId.slice(-8)}`);
  return parts.join(' · ');
}
