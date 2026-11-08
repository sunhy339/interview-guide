package interview.guide.modules.agent.model;

import java.time.LocalDateTime;

/** Agent Run 事件的低敏查询结果；不返回正文，只返回稳定关联和摘要哈希。 */
public record AgentRunEventResponse(
    String runId,
    String agentSessionId,
    String eventType,
    String modelTurnId,
    String toolCallId,
    String toolName,
    String argumentsHash,
    String resultHash,
    String providerResponseId,
    String providerResponseIdsHash,
    String providerToolCallIdsHash,
    Integer providerResponseCount,
    Integer providerToolCallCount,
    String status,
    LocalDateTime createdAt
) {
    public static AgentRunEventResponse from(AgentRunEventEntity event) {
        return new AgentRunEventResponse(
            event.getRunId(), event.getAgentSessionId(), event.getEventType(),
            event.getModelTurnId(), event.getToolCallId(), event.getToolName(),
            event.getArgumentsHash(), event.getResultHash(), event.getProviderResponseId(),
            event.getProviderResponseIdsHash(), event.getProviderToolCallIdsHash(),
            event.getProviderResponseCount(), event.getProviderToolCallCount(),
            event.getStatus(), event.getCreatedAt()
        );
    }
}
