package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent 动作在前端展示的安全摘要。 */
public record AgentActionResponse(
    String actionId,
    String interviewSessionId,
    String agentSessionId,
    String toolId,
    String riskLevel,
    AgentActionStatus status,
    String title,
    String previewText,
    String draftContent,
    String resultSummary,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime confirmedAt,
    LocalDateTime completedAt,
    String resultContractVersion,
    String resultCode,
    String resultEffect,
    String resultResourceType,
    String resultResourceId
) {
    /** 兼容旧测试和旧调用方；执行结果字段为空表示尚未执行或历史数据。 */
    public AgentActionResponse(
        String actionId,
        String interviewSessionId,
        String agentSessionId,
        String toolId,
        String riskLevel,
        AgentActionStatus status,
        String title,
        String previewText,
        String draftContent,
        String resultSummary,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime confirmedAt,
        LocalDateTime completedAt
    ) {
        this(actionId, interviewSessionId, agentSessionId, toolId, riskLevel, status, title,
            previewText, draftContent, resultSummary, createdAt, updatedAt, confirmedAt,
            completedAt, null, null, null, null, null);
    }

    public static AgentActionResponse from(AgentActionRequestEntity entity) {
        return new AgentActionResponse(
            entity.getActionId(),
            entity.getInterviewSessionId(),
            entity.getAgentSessionId(),
            entity.getToolId(),
            entity.getRiskLevel(),
            entity.getStatus(),
            entity.getTitle(),
            entity.getPreviewText(),
            entity.getDraftContent(),
            entity.getResultSummary(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getConfirmedAt(),
            entity.getCompletedAt(),
            entity.getResultContractVersion(),
            entity.getResultCode(),
            entity.getResultEffect(),
            entity.getResultResourceType(),
            entity.getResultResourceId()
        );
    }
}
