export type AgentUserSkillStatus = 'PENDING_REVIEW' | 'ENABLED' | 'DISABLED' | 'REJECTED';

export interface AgentUserSkill {
  skillId: string;
  skillKey: string;
  originalFilename: string;
  name: string;
  description?: string | null;
  content?: string | null;
  version: number;
  status: AgentUserSkillStatus;
  securityFlagged: boolean;
  securityNotice?: string | null;
  reviewedAt?: string | null;
  enabledAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}
