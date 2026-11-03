package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent 当前上下文绑定的安全展示对象。 */
public record AgentContextBindingResponse(
    String contextType,
    String interviewSessionId,
    String resourceId,
    String resourceName,
    String scope,
    boolean active,
    LocalDateTime attachedAt
) {
    public static AgentContextBindingResponse from(AgentSessionContextEntity entity) {
        return new AgentContextBindingResponse(
            entity.getContextType(), entity.getInterviewSessionId(), entity.getResourceId(), entity.getResourceName(),
            entity.getScope(), entity.isActive(), entity.getAttachedAt()
        );
    }
}
