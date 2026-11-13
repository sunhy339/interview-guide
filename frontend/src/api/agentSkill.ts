import request from './request';
import { getAgentMemoryKey } from '../utils/clientIdentity';
import type { AgentUserSkill } from '../types/agentSkill';

function agentHeaders() {
  return { 'X-User-Id': getAgentMemoryKey() };
}

export const agentSkillApi = {
  list(): Promise<AgentUserSkill[]> {
    return request.get<AgentUserSkill[]>('/api/agent/skills', { headers: agentHeaders() });
  },

  get(skillId: string): Promise<AgentUserSkill> {
    return request.get<AgentUserSkill>(`/api/agent/skills/${encodeURIComponent(skillId)}`, {
      headers: agentHeaders(),
    });
  },

  upload(file: File): Promise<AgentUserSkill> {
    const formData = new FormData();
    formData.append('file', file);
    return request.upload<AgentUserSkill>('/api/agent/skills', formData, {
      headers: agentHeaders(),
    });
  },

  approve(skillId: string, confirmSecurity = false): Promise<AgentUserSkill> {
    const query = confirmSecurity ? '?confirmSecurity=true' : '';
    return request.post<AgentUserSkill>(
      `/api/agent/skills/${encodeURIComponent(skillId)}/approve${query}`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  enable(skillId: string): Promise<AgentUserSkill> {
    return request.post<AgentUserSkill>(
      `/api/agent/skills/${encodeURIComponent(skillId)}/enable`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  disable(skillId: string): Promise<AgentUserSkill> {
    return request.post<AgentUserSkill>(
      `/api/agent/skills/${encodeURIComponent(skillId)}/disable`,
      undefined,
      { headers: agentHeaders() },
    );
  },

  reject(skillId: string): Promise<AgentUserSkill> {
    return request.post<AgentUserSkill>(
      `/api/agent/skills/${encodeURIComponent(skillId)}/reject`,
      undefined,
      { headers: agentHeaders() },
    );
  },
};
