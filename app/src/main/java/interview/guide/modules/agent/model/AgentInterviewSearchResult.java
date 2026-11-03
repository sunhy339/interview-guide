package interview.guide.modules.agent.model;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import java.time.LocalDateTime;

/** Agent 搜索面试时只返回用于消歧的轻量元数据。 */
public record AgentInterviewSearchResult(
    String sessionId,
    String title,
    String skillId,
    String status,
    Integer overallScore,
    Long resumeId,
    LocalDateTime createdAt
) {
    public static AgentInterviewSearchResult from(InterviewSessionEntity session) {
        return new AgentInterviewSearchResult(
            session.getSessionId(),
            session.getTitle(),
            session.getSkillId(),
            session.getStatus() == null ? null : session.getStatus().name(),
            session.getOverallScore(),
            session.getResumeId(),
            session.getCreatedAt()
        );
    }
}
