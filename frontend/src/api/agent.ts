import { streamSse } from './stream.ts';
import request from './request.ts';
import type {
  AgentConversationResponse,
  AgentConversationSummary,
  AgentAuditEvent,
  AgentAction,
  AgentTrainingTask,
  AgentTrainingTaskStatus,
  AgentMemoryResponse,
  AgentMessageRequest,
  AgentStreamEvent,
  AgentUsageSummary,
  AgentUsageRun,
  AgentContextBinding,
  AgentContextAsset,
  AgentKnowledgeBaseOption,
} from '../types/agent.ts';
import { getAgentMemoryKey } from '../utils/clientIdentity.ts';

function agentHeaders() {
  return { 'X-User-Id': getAgentMemoryKey() };
}

interface AgentStreamHandlers {
  onEvent: (event: AgentStreamEvent) => void;
  onComplete: () => void;
  onError: (error: Error) => void;
}

export const agentApi = {
  createGeneralConversation(title?: string, idempotencyKey?: string): Promise<AgentConversationSummary> {
    const body = {
      ...(title?.trim() ? { title: title.trim() } : {}),
      ...(idempotencyKey ? { idempotencyKey } : {}),
    };
    return request.post<AgentConversationSummary>(
      '/api/agent/sessions',
      Object.keys(body).length > 0 ? body : undefined,
      { headers: agentHeaders() },
    );
  },

  listGeneralConversations(): Promise<AgentConversationSummary[]> {
    return request.get<AgentConversationSummary[]>('/api/agent/sessions', { headers: agentHeaders() });
  },

  getGeneralConversation(agentSessionId: string): Promise<AgentConversationResponse> {
    return request.get<AgentConversationResponse>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}`,
      { headers: agentHeaders() },
    );
  },

  renameGeneralConversation(agentSessionId: string, title: string): Promise<AgentConversationSummary> {
    return request.patch<AgentConversationSummary>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}`,
      { title },
      { headers: agentHeaders() },
    );
  },

  archiveGeneralConversation(agentSessionId: string): Promise<void> {
    return request.delete<void>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}`,
      { headers: agentHeaders() },
    );
  },

  getContextBindings(agentSessionId: string): Promise<AgentContextBinding[]> {
    return request.get<AgentContextBinding[]>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}/context`,
      { headers: agentHeaders() },
    );
  },

  searchInterviews(query = ''): Promise<Array<{ sessionId: string; title?: string | null; createdAt?: string | null }>> {
    const suffix = query.trim() ? `?q=${encodeURIComponent(query.trim())}` : '';
    return request.get<Array<{ sessionId: string; title?: string | null; createdAt?: string | null }>>(
      `/api/agent/interviews/search${suffix}`,
      { headers: agentHeaders() },
    );
  },

  attachContext(
    agentSessionId: string,
    sourceType: string,
    resourceId: string,
    scope: 'SESSION' | 'TURN' = 'SESSION',
  ): Promise<AgentContextBinding> {
    return request.post<AgentContextBinding>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}/contexts`,
      { sourceType, resourceId, scope },
      { headers: agentHeaders() },
    );
  },

  clearContext(agentSessionId: string): Promise<void> {
    return request.delete<void>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}/context`,
      { headers: agentHeaders() },
    );
  },

  listGeneralActions(agentSessionId: string): Promise<AgentAction[]> {
    return request.get<AgentAction[]>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}/actions`,
      { headers: agentHeaders() },
    );
  },

  cancelGeneralRun(agentSessionId: string): Promise<AgentConversationResponse> {
    return request.post<AgentConversationResponse>(
      `/api/agent/sessions/${encodeURIComponent(agentSessionId)}/cancel`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  uploadContextAsset(file: File): Promise<AgentContextAsset> {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<AgentContextAsset>('/api/agent/context-assets', formData, {
      headers: agentHeaders(),
    });
  },

  listContextAssets(): Promise<AgentContextAsset[]> {
    return request.get<AgentContextAsset[]>('/api/agent/context-assets', { headers: agentHeaders() });
  },

  listKnowledgeBases(): Promise<AgentKnowledgeBaseOption[]> {
    return request.get<AgentKnowledgeBaseOption[]>('/api/agent/knowledge-bases', {
      headers: agentHeaders(),
    });
  },

  approveContextAsset(assetId: string, confirmSecurity = false): Promise<AgentContextAsset> {
    const query = confirmSecurity ? '?confirmSecurity=true' : '';
    return request.post<AgentContextAsset>(
      `/api/agent/context-assets/${encodeURIComponent(assetId)}/approve${query}`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  requestKnowledgeBaseImport(assetId: string): Promise<AgentContextAsset> {
    return request.post<AgentContextAsset>(
      `/api/agent/context-assets/${encodeURIComponent(assetId)}/knowledge-base-request`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  confirmKnowledgeBaseImport(assetId: string, name?: string, category?: string): Promise<AgentContextAsset> {
    const params = new URLSearchParams();
    if (name?.trim()) params.set('name', name.trim());
    if (category?.trim()) params.set('category', category.trim());
    const query = params.toString() ? `?${params.toString()}` : '';
    return request.post<AgentContextAsset>(
      `/api/agent/context-assets/${encodeURIComponent(assetId)}/knowledge-base/confirm${query}`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  rejectContextAsset(assetId: string): Promise<void> {
    return request.delete<void>(
      `/api/agent/context-assets/${encodeURIComponent(assetId)}`,
      { headers: agentHeaders() },
    );
  },

  getUsageSummary(): Promise<AgentUsageSummary> {
    return request.get<AgentUsageSummary>('/api/agent/usage/summary', {
      headers: agentHeaders(),
    });
  },

  getUsageRuns(limit = 20): Promise<AgentUsageRun[]> {
    return request.get<AgentUsageRun[]>(
      `/api/agent/usage/runs?limit=${Math.max(1, Math.min(100, limit))}`,
      { headers: agentHeaders() },
    );
  },

  getAuditEvents(limit = 20): Promise<AgentAuditEvent[]> {
    return request.get<AgentAuditEvent[]>(
      `/api/agent/audit/events?limit=${Math.max(1, Math.min(100, limit))}`,
      { headers: agentHeaders() },
    );
  },

  getConversation(sessionId: string, agentSessionId?: string | null): Promise<AgentConversationResponse> {
    const query = agentSessionId ? `?agentSessionId=${encodeURIComponent(agentSessionId)}` : '';
    return request.get<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversation${query}`,
      { headers: agentHeaders() },
    );
  },

  listActions(sessionId: string, agentSessionId?: string | null): Promise<AgentAction[]> {
    const query = agentSessionId ? `?agentSessionId=${encodeURIComponent(agentSessionId)}` : '';
    return request.get<AgentAction[]>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/actions${query}`,
      { headers: agentHeaders() },
    );
  },

  proposeTrainingPlanDraft(
    sessionId: string,
    agentSessionId: string,
    content: string,
    idempotencyKey?: string,
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/training-plan-draft`,
      { content, idempotencyKey },
      { headers: agentHeaders() },
    );
  },

  proposePracticeQuestionsDraft(
    sessionId: string,
    agentSessionId: string,
    content: string,
    idempotencyKey?: string,
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/practice-questions-draft`,
      { content, idempotencyKey },
      { headers: agentHeaders() },
    );
  },

  proposeTrainingTaskDraft(
    sessionId: string,
    agentSessionId: string,
    content: string,
    idempotencyKey?: string,
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/training-task-draft`,
      { content, idempotencyKey },
      { headers: agentHeaders() },
    );
  },

  proposeInterviewReportExportDraft(
    sessionId: string,
    agentSessionId: string,
    idempotencyKey?: string,
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/interview-report-export-draft`,
      idempotencyKey ? { idempotencyKey } : undefined,
      { headers: agentHeaders() },
    );
  },

  proposeResumeEditDraft(
    sessionId: string,
    agentSessionId: string,
    content: string,
    idempotencyKey?: string,
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/resume-edit-draft`,
      { content, idempotencyKey },
      { headers: agentHeaders() },
    );
  },

  proposeInterviewScheduleDraft(
    sessionId: string,
    agentSessionId: string,
    payload: {
      companyName: string;
      position: string;
      interviewTime: string;
      interviewType?: string;
      meetingLink?: string;
      roundNumber?: number;
      interviewer?: string;
      notes?: string;
      idempotencyKey?: string;
    },
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/interview-schedule-draft`,
      payload,
      { headers: agentHeaders() },
    );
  },

  proposeMemoryDraft(
    sessionId: string,
    agentSessionId: string,
    content: string,
    category: 'weakness' | 'goal',
    jobScope?: string,
    idempotencyKey?: string,
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/memory-draft`,
      { content, category, jobScope, idempotencyKey },
      { headers: agentHeaders() },
    );
  },

  proposeInterviewTitleDraft(
    sessionId: string,
    agentSessionId: string,
    title: string,
    idempotencyKey?: string,
  ): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}/actions/interview-title-draft`,
      { content: title, idempotencyKey },
      { headers: agentHeaders() },
    );
  },

  confirmAction(actionId: string): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/actions/${encodeURIComponent(actionId)}/confirm`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  downloadConfirmedInterviewReport(actionId: string): Promise<Blob> {
    return request.download(
      `/api/agent/actions/${encodeURIComponent(actionId)}/interview-report`,
      { headers: agentHeaders() },
    );
  },

  retryAction(actionId: string): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/actions/${encodeURIComponent(actionId)}/retry`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  updateActionDraft(actionId: string, content: string): Promise<AgentAction> {
    return request.patch<AgentAction>(
      `/api/agent/actions/${encodeURIComponent(actionId)}`,
      { content },
      { headers: agentHeaders() },
    );
  },

  cancelAction(actionId: string): Promise<AgentAction> {
    return request.post<AgentAction>(
      `/api/agent/actions/${encodeURIComponent(actionId)}/cancel`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  listTrainingTasks(status?: AgentTrainingTaskStatus): Promise<AgentTrainingTask[]> {
    const query = status ? `?status=${encodeURIComponent(status)}` : '';
    return request.get<AgentTrainingTask[]>(`/api/agent/training-tasks${query}`, {
      headers: agentHeaders(),
    });
  },

  updateTrainingTask(taskId: string, status: AgentTrainingTaskStatus): Promise<AgentTrainingTask> {
    return request.patch<AgentTrainingTask>(
      `/api/agent/training-tasks/${encodeURIComponent(taskId)}`,
      { status },
      { headers: agentHeaders() },
    );
  },

  listConversations(sessionId: string): Promise<AgentConversationSummary[]> {
    return request.get<AgentConversationSummary[]>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations`,
      { headers: agentHeaders() },
    );
  },

  createConversation(
    sessionId: string,
    title?: string,
    idempotencyKey?: string,
  ): Promise<AgentConversationSummary> {
    const body = {
      ...(title?.trim() ? { title: title.trim() } : {}),
      ...(idempotencyKey ? { idempotencyKey } : {}),
    };
    return request.post<AgentConversationSummary>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations`,
      Object.keys(body).length > 0 ? body : undefined,
      { headers: agentHeaders() },
    );
  },

  renameConversation(sessionId: string, agentSessionId: string, title: string): Promise<AgentConversationSummary> {
    return request.patch<AgentConversationSummary>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}`,
      { title },
      { headers: agentHeaders() },
    );
  },

  archiveConversation(sessionId: string, agentSessionId: string): Promise<void> {
    return request.delete<void>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/conversations/${encodeURIComponent(agentSessionId)}`,
      { headers: agentHeaders() },
    );
  },

  cancelRun(sessionId: string, agentSessionId?: string | null): Promise<AgentConversationResponse> {
    const query = agentSessionId ? `?agentSessionId=${encodeURIComponent(agentSessionId)}` : '';
    return request.post<AgentConversationResponse>(
      `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/cancel${query}`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  clearMemory(): Promise<void> {
    return request.delete<void>('/api/agent/memory', { headers: agentHeaders() });
  },

  getMemory(): Promise<AgentMemoryResponse> {
    return request.get<AgentMemoryResponse>('/api/agent/memory', { headers: agentHeaders() });
  },

  updateMemoryItem(itemId: string, content: string, jobScope?: string): Promise<AgentMemoryResponse> {
    return request.patch<AgentMemoryResponse>(
      `/api/agent/memory/items/${encodeURIComponent(itemId)}`,
      { content, jobScope: jobScope?.trim() ?? '' },
      { headers: agentHeaders() },
    );
  },

  deleteMemoryItem(itemId: string): Promise<AgentMemoryResponse> {
    return request.delete<AgentMemoryResponse>(
      `/api/agent/memory/items/${encodeURIComponent(itemId)}`,
      { headers: agentHeaders() },
    );
  },

  streamMessage(
    sessionId: string,
    request: AgentMessageRequest,
    handlers: AgentStreamHandlers,
    signal?: AbortSignal,
  ): Promise<void> {
    return streamSse({
      url: `/api/agent/interview-sessions/${encodeURIComponent(sessionId)}/messages/stream`,
      init: {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...agentHeaders(),
        },
        body: JSON.stringify({ ...request, memoryKey: request.memoryKey ?? getAgentMemoryKey() }),
        signal,
      },
      parseMode: 'event',
      // Agent 事件载荷是 JSON，去掉 SSE data: 后的协议分隔空格，避免回调拿到多余前缀。
      trimDataPrefixSpace: true,
      dataJoiner: '',
      preserveBusinessErrorEvents: true,
      onMessage: (chunk) => {
        try {
          handlers.onEvent(JSON.parse(chunk) as AgentStreamEvent);
        } catch {
          // 解析失败必须中断当前流；否则后续 onComplete 可能把页面状态覆盖成成功。
          throw new Error('Agent 返回了无法识别的事件');
        }
      },
      onComplete: handlers.onComplete,
      onError: handlers.onError,
    });
  },

  streamGeneralMessage(
    agentSessionId: string,
    requestBody: AgentMessageRequest,
    handlers: AgentStreamHandlers,
    signal?: AbortSignal,
  ): Promise<void> {
    return streamSse({
      url: `/api/agent/sessions/${encodeURIComponent(agentSessionId)}/messages/stream`,
      init: {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          ...agentHeaders(),
        },
        body: JSON.stringify({ ...requestBody, memoryKey: requestBody.memoryKey ?? getAgentMemoryKey() }),
        signal,
      },
      parseMode: 'event',
      trimDataPrefixSpace: true,
      dataJoiner: '',
      preserveBusinessErrorEvents: true,
      onMessage: (chunk) => {
        try {
          handlers.onEvent(JSON.parse(chunk) as AgentStreamEvent);
        } catch {
          throw new Error('Agent 返回了无法识别的事件');
        }
      },
      onComplete: handlers.onComplete,
      onError: handlers.onError,
    });
  },
};
