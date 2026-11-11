export type AgentActionIntent =
  | { type: 'schedule'; suggestedTime?: string; companyName?: string; position?: string; timeZone?: string }
  | { type: 'scheduleCancel' }
  | { type: 'scheduleUpdate' }
  | {
    type: 'mockInterview';
    skillId: string;
    skillLabel: string;
    difficulty: 'junior' | 'mid' | 'senior';
    questionCount: number;
    useResume: boolean;
  }
  | { type: 'trainingTask' }
  | { type: 'memory' }
  | { type: 'title' }
  | { type: 'reportExport' }
  | { type: 'resumeEdit' };

function pad(value: number) {
  return String(value).padStart(2, '0');
}

export function formatLocalDateTime(value: Date) {
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}`;
}

function atTime(value: Date, hour: number, minute = 0) {
  const result = new Date(value);
  result.setHours(hour, minute, 0, 0);
  return result;
}

function cleanDetail(value: string | undefined) {
  return value?.replace(/[，。,；;。\s]+$/g, '').trim() || undefined;
}

const QUICK_INTERVIEW_SKILLS: Array<{
  id: string;
  label: string;
  pattern: RegExp;
}> = [
  { id: 'ai-agent-dev', label: 'AI Agent 开发', pattern: /(ai\s*agent|agent\s*开发|智能体)/i },
  { id: 'python-backend', label: 'Python 后端开发', pattern: /(python\s*后端|python\s*开发)/i },
  { id: 'system-design', label: '系统设计', pattern: /(系统设计|架构设计)/ },
  { id: 'frontend', label: '前端工程', pattern: /(前端|frontend)/i },
  { id: 'java-backend', label: 'Java 后端开发', pattern: /(java\s*后端|java\s*开发)/i },
  { id: 'java-backend', label: '后端开发', pattern: /(后端|backend)/i },
];

function extractQuickInterviewSkill(message: string) {
  const matched = QUICK_INTERVIEW_SKILLS.find((item) => item.pattern.test(message));
  return matched ? { skillId: matched.id, skillLabel: matched.label } : undefined;
}

function extractQuickInterviewDifficulty(message: string): 'junior' | 'mid' | 'senior' {
  if (/(高级|资深|senior)/i.test(message)) return 'senior';
  if (/(初级|初阶|校招|junior)/i.test(message)) return 'junior';
  return 'mid';
}

function extractQuickInterviewQuestionCount(message: string) {
  const matched = message.match(/(\d{1,2})\s*(?:道|题|个(?:问题|题目))/);
  if (!matched) return 6;
  const count = Number(matched[1]);
  return Number.isInteger(count) && count >= 3 && count <= 20 ? count : null;
}

/**
 * 识别明确的“直接开始一场模拟面试”命令。
 *
 * <p>这是前端快捷入口，不替代 Agent 的通用意图分类：只有用户明确表达
 * 开始/创建模拟面试，并给出可映射的方向时才返回，避免“如何进行模拟面试”
 * 或“复盘面试报告”被误判成创建动作。</p>
 */
export function detectQuickInterviewIntent(message: string): Extract<AgentActionIntent, { type: 'mockInterview' }> | null {
  const normalized = message.replace(/\s+/g, ' ').trim();
  if (!normalized
    || /(怎么|如何|什么是|介绍|了解).{0,8}(模拟面试|文字面试)/.test(normalized)
    || /(复盘|报告|会议|日程|预约|取消|改期).{0,10}(面试|模拟)/.test(normalized)) {
    return null;
  }
  const startsInterview = /(帮我|请|开始|进行|创建|新建|来一场|安排).{0,40}(模拟面试|文字面试|面试)/.test(normalized)
    || /(模拟面试|文字面试).{0,40}(开始|进行|创建|来一场)/.test(normalized);
  if (!startsInterview) return null;

  const skill = extractQuickInterviewSkill(normalized);
  if (!skill) return null;
  const questionCount = extractQuickInterviewQuestionCount(normalized);
  if (questionCount == null) return null;

  const explicitlyWithoutResume = /(不使用|不用|无需|不要|不带|无).{0,4}简历/.test(normalized);
  const explicitlyWithResume = /(使用|带上|根据|参考).{0,4}简历/.test(normalized);
  return {
    type: 'mockInterview',
    ...skill,
    difficulty: extractQuickInterviewDifficulty(normalized),
    questionCount,
    useResume: explicitlyWithResume && !explicitlyWithoutResume,
  };
}

/** 从补充信息中提取日程字段，避免把“公司是...职位是...”再次交给复盘模型。 */
export function extractScheduleDetails(message: string) {
  const normalized = message.replace(/\s+/g, ' ').trim();
  const companyName = cleanDetail(normalized.match(/公司(?:名称)?\s*(?:是|为|：|:)\s*([^，。,；;\n]+)/)?.[1]);
  const position = cleanDetail(normalized.match(/(?:职位|岗位)\s*(?:是|为|：|:)\s*([^，。,；;\n]+)/)?.[1]);
  const timeZone = /北京(?:时间|时区)|中国标准时间|\bCST\b/i.test(normalized)
    ? 'Asia/Shanghai'
    : undefined;
  return { companyName, position, timeZone };
}

export function isScheduleDetailsMessage(message: string) {
  const details = extractScheduleDetails(message);
  return Boolean(details.companyName || details.position || details.timeZone);
}

function nextMonday(value: Date) {
  const result = new Date(value);
  const daysUntilMonday = ((8 - result.getDay()) % 7) || 7;
  result.setDate(result.getDate() + daysUntilMonday);
  return result;
}

/** 将用户自然语言里的常见复习时间转换为 datetime-local 可编辑值。 */
export function suggestScheduleTime(message: string, now = new Date()) {
  const normalized = message.trim();
  let date = new Date(now);
  if (/下周/.test(normalized)) {
    date = nextMonday(date);
  } else if (/后天/.test(normalized)) {
    date.setDate(date.getDate() + 2);
  } else if (/明天/.test(normalized)) {
    date.setDate(date.getDate() + 1);
  } else if (/周末/.test(normalized)) {
    const daysUntilSaturday = ((6 - date.getDay()) + 7) % 7 || 7;
    date.setDate(date.getDate() + daysUntilSaturday);
  } else {
    const dateMatch = normalized.match(/(\d{1,2})月(\d{1,2})日/);
    if (dateMatch) {
      const year = date.getFullYear();
      date = new Date(year, Number(dateMatch[1]) - 1, Number(dateMatch[2]));
      if (date.getTime() < now.getTime()) date.setFullYear(year + 1);
    } else {
      date.setDate(date.getDate() + 1);
    }
  }

  const timeMatch = normalized.match(/(?:上午|早上|下午|晚上|今晚|晚间)?\s*(\d{1,2})(?:点|时|:)(\d{1,2})?/);
  if (timeMatch) {
    let hour = Number(timeMatch[1]);
    const minute = Number(timeMatch[2] || 0);
    if (/(下午|晚上|今晚|晚间)/.test(timeMatch[0]) && hour < 12) hour += 12;
    date = atTime(date, Math.min(23, hour), Math.min(59, minute));
  } else if (/今晚|晚上|晚间/.test(normalized)) {
    date = atTime(date, 19);
  } else {
    date = atTime(date, 19);
  }
  return formatLocalDateTime(date);
}

/** 只识别明确的“安排复习”意图，避免普通复盘内容误触发写入流程。 */
export function detectAgentActionIntent(message: string, now = new Date()): AgentActionIntent | null {
  const normalized = message.replace(/\s+/g, ' ').trim();
  if (/(取消|撤销|删掉|去掉).{0,16}(面试|会议|日程|预约)/.test(normalized)
    || /(面试|会议|日程|预约).{0,16}(取消|撤销|删掉|去掉)/.test(normalized)) {
    return { type: 'scheduleCancel' };
  }
  if (/(修改|改期|改到|调整|更换|更新).{0,16}(面试|会议|日程|预约)/.test(normalized)
    || /(面试|会议|日程|预约).{0,16}(修改|改期|改到|调整|更换|更新)/.test(normalized)) {
    return { type: 'scheduleUpdate' };
  }
  if (!normalized || /(不要|不用|无需|取消).{0,6}(安排|预约|日程|导出|下载|保存|创建|修改|重命名|任务|简历|记忆)/.test(normalized)) return null;
  const quickInterview = detectQuickInterviewIntent(normalized);
  if (quickInterview) return quickInterview;
  if (/(导出|下载).{0,8}(报告|面试报告|pdf)/i.test(normalized)
    || /(报告|面试报告).{0,8}(导出|下载)/i.test(normalized)) {
    return { type: 'reportExport' };
  }
  if (/(优化|修改|编辑|改写).{0,8}简历/.test(normalized)
    || /简历.{0,8}(优化|修改|编辑|改写)/.test(normalized)) {
    return { type: 'resumeEdit' };
  }
  if (/(保存|记住|记录|加入|写入).{0,10}(长期记忆|记忆|薄弱点|偏好)/.test(normalized)
    || /(长期记忆|记忆).{0,8}(保存|记录|记住)/.test(normalized)) {
    return { type: 'memory' };
  }
  if (/(修改|重命名|改).{0,8}(面试记录|面试名称|标题)/.test(normalized)
    || /给这场面试起个名字/.test(normalized)) {
    return { type: 'title' };
  }
  if (/(创建|新建|加入|拆成|安排).{0,12}(训练任务|练习任务|任务清单|待办)/.test(normalized)
    || /把.{0,12}(作为|列为).{0,8}任务/.test(normalized)) {
    return { type: 'trainingTask' };
  }
  const asksForSchedule = /(安排|预约|加入日程|排个|排一下|提醒我|设置).{0,12}(复习|练习|巩固|日程|时间|准备面试)/.test(normalized)
    || /(复习|练习|巩固|准备面试).{0,12}(下周|明天|后天|周末|今晚|日程|时间)/.test(normalized);
  const asksForInterviewMeeting = /(安排|预约|创建|添加).{0,16}(面试会议|面试|会议|日程)/.test(normalized)
    && /(下周|明天|后天|周末|今晚|晚上|\d{1,2}月\d{1,2}日|\d{1,2}(?:点|时|:))/.test(normalized);
  const details = extractScheduleDetails(normalized);
  if (!asksForSchedule && !asksForInterviewMeeting
    && !details.companyName && !details.position && !details.timeZone) return null;
  const hasExplicitTime = /(下周|明天|后天|周末|今晚|晚上|\d{1,2}月\d{1,2}日|\d{1,2}(?:点|时|:))/.test(normalized);
  return {
    type: 'schedule',
    ...(hasExplicitTime ? { suggestedTime: suggestScheduleTime(normalized, now) } : {}),
    ...(details.companyName ? { companyName: details.companyName } : {}),
    ...(details.position ? { position: details.position } : {}),
    ...(details.timeZone ? { timeZone: details.timeZone } : {}),
  };
}
